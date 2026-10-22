package com.example.nms.provider;

import static com.example.nms.support.SubmitRequests.IDEMPOTENCY_KEY;
import static com.example.nms.support.SubmitRequests.PATH;
import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.SubmitRequests.newKey;
import static com.example.nms.support.SubmitRequests.valid;
import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.nms.delivery.DeliveryWorker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #11 end to end on a real server: submit, one worker poll (the poller is off), the simulated
 * vendors call {@code POST /api/v1/webhooks/{provider}} over HTTP with their demo tokens, and the
 * status API shows the final states.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "nms.simulator.callbacks.enabled=true",
      "nms.simulator.callbacks.delay=" + SimulatedCallbacksIT.DELAY_MILLIS + "ms"
    })
class SimulatedCallbacksIT {

  static final long DELAY_MILLIS = 200;

  private final HttpClient client = HttpClient.newHttpClient();

  @LocalServerPort private int port;
  @Autowired private DeliveryWorker worker;
  @Autowired private SimulatedCallbacks callbacks;
  @Autowired private SimulatedEmailProvider email;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void acceptedSendsAreCalledBackOverHttpAndStatusShowsTheOutcome() throws Exception {
    Map<String, UUID> ids = submit("user-ok", "user-bounce", "user-cb-failed", "user-silent");
    int scheduledBefore = callbacks.scheduledCallbacks();

    assertThat(worker.runOnce()).isEqualTo(7);
    assertThat(status(ids.get("user-ok")).get("overallStatus").stringValue())
        .as("before the callbacks")
        .isEqualTo("AWAITING_CONFIRMATION");

    // user-ok: email + SMS delivered; user-bounce: email only; user-cb-failed: both; silent: none.
    assertThat(callbacks.scheduledCallbacks() - scheduledBefore).isEqualTo(5);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(overall(ids.get("user-ok"))).isEqualTo("DELIVERED");
              assertThat(overall(ids.get("user-bounce"))).isEqualTo("FAILED");
              assertThat(overall(ids.get("user-cb-failed"))).isEqualTo("FAILED");
            });

    JsonNode ok = status(ids.get("user-ok"));
    for (JsonNode d : ok.get("deliveries")) {
      assertThat(d.get("status").stringValue()).isEqualTo("DELIVERED");
      assertThat(d.has("sentAt") && d.has("finalizedAt")).isTrue();
    }
    assertThat(reasons(status(ids.get("user-bounce")))).containsExactly("BOUNCED");
    assertThat(reasons(status(ids.get("user-cb-failed"))))
        .containsExactly("CALLBACK_FAILED", "CALLBACK_FAILED");
    assertThat(overall(ids.get("user-silent"))).isEqualTo("AWAITING_CONFIRMATION");
    assertThat(auditTypes(ids.get("user-ok"))).endsWith("SENT", "SENT", "DELIVERED", "DELIVERED");
  }

  @Test
  void dedupedResendSchedulesNoSecondCallback() throws Exception {
    UUID id = submit("user-email-only").get("user-email-only");
    worker.runOnce();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(overall(id)).isEqualTo("DELIVERED"));
    UUID deliveryId =
        UUID.fromString(status(id).get("deliveries").get(0).get("deliveryId").stringValue());
    int scheduled = callbacks.scheduledCallbacks();

    // A resend after acceptance (e.g. a lease reclaim) carries the same deliveryId.
    email.send(new SendRequest(deliveryId, "ok.delivered@sim.test", "subject", "body"));
    Thread.sleep(3 * DELAY_MILLIS);

    assertThat(callbacks.scheduledCallbacks()).isEqualTo(scheduled);
    assertThat(auditTypes(id)).doesNotContain("CALLBACK_IGNORED").endsWith("SENT", "DELIVERED");
  }

  // Helpers

  private Map<String, UUID> submit(String... userIds) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(uri(PATH))
                .header(HEADER, DEMO1)
                .header(IDEMPOTENCY_KEY, newKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(valid(userIds).toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(202);
    Map<String, UUID> ids = new HashMap<>();
    for (JsonNode n : JsonMapper.shared().readTree(response.body()).get("notifications")) {
      ids.put(
          n.get("recipientId").stringValue(),
          UUID.fromString(n.get("notificationId").stringValue()));
    }
    return ids;
  }

  private JsonNode status(UUID id) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(uri(PATH + "/" + id)).header(HEADER, DEMO1).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return JsonMapper.shared().readTree(response.body());
  }

  private String overall(UUID id) throws Exception {
    return status(id).get("overallStatus").stringValue();
  }

  private static List<String> reasons(JsonNode status) {
    return status.get("deliveries").valueStream().map(d -> d.get("reason").stringValue()).toList();
  }

  private List<String> auditTypes(UUID notificationId) {
    return jdbc.queryForList(
        "select type from audit_event where notification_id = ? order by occurred_at, id",
        String.class,
        notificationId);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
