package com.example.nms;

import static com.example.nms.support.SubmitRequests.IDEMPOTENCY_KEY;
import static com.example.nms.support.SubmitRequests.PATH;
import static com.example.nms.support.SubmitRequests.newKey;
import static com.example.nms.support.SubmitRequests.valid;
import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * #13 greenfield end to end over HTTP only: a real server, the background worker polling, and the
 * simulated vendors calling back over HTTP. Every flow is submitted, then polled through the status
 * API until final; history is read back through the audit API. After each test, captured logs and
 * every audit row are checked for message content, contact details, and credentials.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "nms.worker.enabled=true",
      "nms.worker.poll-interval=50ms",
      // Retry waits 50-100ms, then 100-200ms (equal jitter), so the retry path stays fast.
      "nms.worker.backoff-base=100ms",
      "nms.simulator.callbacks.enabled=true",
      "nms.simulator.callbacks.delay=100ms"
    })
@ExtendWith(OutputCaptureExtension.class)
// Stop this context's poller and callback scheduler once the class is done.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GreenfieldE2EIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final String SUBJECT = "E2E subject " + UUID.randomUUID();
  private static final String BODY = "E2E body " + UUID.randomUUID();

  private final HttpClient client = HttpClient.newHttpClient();

  /** Client-supplied secrets of this test, checked for leaks after it. */
  private final Set<String> keysUsed = ConcurrentHashMap.newKeySet();

  /** Delivery ids seen by this test; their log lines prove the leak check saw real output. */
  private final Set<String> deliveriesSeen = ConcurrentHashMap.newKeySet();

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void acceptedSendIsConfirmedByCallbackAndDelivered() throws Exception {
    UUID id = submitNew("user-ok");

    JsonNode status = awaitOverall(id, "DELIVERED");

    assertThat(status.get("recipientId").stringValue()).isEqualTo("user-ok");
    assertThat(status.get("duplicateSubmissions").asLong()).isZero();
    assertThat(channels(status)).containsExactly("EMAIL", "SMS");
    for (JsonNode d : status.get("deliveries")) {
      assertThat(d.get("status").stringValue()).isEqualTo("DELIVERED");
      assertThat(d.get("attempts").asInt()).isOne();
      assertThat(d.has("reason")).isFalse();
      assertThat(instant(d, "finalizedAt")).isAfterOrEqualTo(instant(d, "sentAt"));
    }
    List<JsonNode> history = audit(id);
    assertThat(history.getFirst().get("type").stringValue()).isEqualTo("ACCEPTED");
    for (String channel : List.of("EMAIL", "SMS")) {
      assertThat(typesOn(history, channel))
          .containsExactly("ROUTED", "QUEUED", "SENT", "DELIVERED");
    }
  }

  @Test
  void twoTransientFailuresAreRetriedThenDelivered() throws Exception {
    UUID id = submitNew("user-flaky2");

    JsonNode status = awaitOverall(id, "DELIVERED");

    assertThat(channels(status)).containsExactly("EMAIL", "SMS");
    for (JsonNode d : status.get("deliveries")) {
      assertThat(d.get("status").stringValue()).isEqualTo("DELIVERED");
      assertThat(d.get("attempts").asInt()).isEqualTo(3);
    }
    List<JsonNode> history = audit(id);
    for (String channel : List.of("EMAIL", "SMS")) {
      assertThat(typesOn(history, channel))
          .containsExactly(
              "ROUTED",
              "QUEUED",
              "ATTEMPT_FAILED",
              "RETRY_SCHEDULED",
              "ATTEMPT_FAILED",
              "RETRY_SCHEDULED",
              "SENT",
              "DELIVERED");
      assertThat(detailsOf(history, channel, "ATTEMPT_FAILED"))
          .containsExactly("attempt 1, TRANSIENT, code 503", "attempt 2, TRANSIENT, code 503");
    }
  }

  @Test
  void duplicateSubmissionReplaysTheOriginalAndIsCounted() throws Exception {
    String key = newKey();
    String body = body("user-email-only");
    HttpResponse<String> first = submit(key, body);
    HttpResponse<String> replay = submit(key, body);

    assertThat(first.statusCode()).isEqualTo(202);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json(replay.body())).isEqualTo(json(first.body()));
    UUID id = notificationIds(first).get("user-email-only");

    JsonNode status = awaitOverall(id, "DELIVERED");

    assertThat(status.get("duplicateSubmissions").asLong()).isOne();
    assertThat(channels(status)).containsExactly("EMAIL");
    assertThat(types(audit(id))).containsOnlyOnce("ACCEPTED", "DUPLICATE_SUBMISSION");
  }

  @Test
  void bouncedEmailEndsFailedWithReasonBounced() throws Exception {
    UUID id = submitNew("user-bounce");

    JsonNode status = awaitOverall(id, "FAILED");

    JsonNode email = status.get("deliveries").get(0);
    assertThat(channels(status)).containsExactly("EMAIL");
    assertThat(email.get("status").stringValue()).isEqualTo("FAILED");
    assertThat(email.get("reason").stringValue()).isEqualTo("BOUNCED");
    assertThat(email.get("attempts").asInt()).isOne();
    List<JsonNode> history = audit(id);
    assertThat(typesOn(history, "EMAIL")).containsExactly("ROUTED", "QUEUED", "SENT", "FAILED");
    assertThat(detailsOf(history, "EMAIL", "FAILED"))
        .containsExactly("BOUNCED, code MAILBOX_UNAVAILABLE");
  }

  /**
   * Leak check (FR-6.2, NFR-3). Captured output spans the whole class (context startup, every test,
   * the worker's and callbacks' background threads); audit rows accumulate across the class.
   */
  @AfterEach
  void noContentContactDetailsOrCredentialsInLogsOrAudit(CapturedOutput output) {
    List<String> forbidden = new ArrayList<>(List.of(SUBJECT, BODY, DEMO1, "@sim.test", "+1555"));
    for (Map<String, Object> contact : jdbc.queryForList("select email, phone from recipient")) {
      contact.values().stream()
          .filter(Objects::nonNull)
          .map(Object::toString)
          .forEach(forbidden::add);
    }
    forbidden.addAll(keysUsed);

    String logs = output.getAll();
    assertThat(deliveriesSeen).isNotEmpty();
    assertThat(logs).contains(deliveriesSeen);
    String auditRows =
        jdbc.queryForList("select * from audit_event").stream()
            .map(Map::toString)
            .collect(Collectors.joining("\n"));
    assertThat(auditRows).isNotEmpty();
    for (String value : forbidden) {
      assertThat(logs).as("logs").doesNotContain(value);
      assertThat(auditRows).as("audit rows").doesNotContain(value);
    }
  }

  // HTTP helpers: the test talks to the service only through its API.

  private UUID submitNew(String userId) throws Exception {
    HttpResponse<String> response = submit(newKey(), body(userId));
    assertThat(response.statusCode()).isEqualTo(202);
    JsonNode notification = json(response.body()).get("notifications").get(0);
    assertThat(notification.get("status").stringValue()).isEqualTo("ACCEPTED");
    return notificationIds(response).get(userId);
  }

  private static String body(String userId) {
    ObjectNode body = valid(userId);
    body.put("subject", SUBJECT);
    body.put("body", BODY);
    return body.toString();
  }

  private HttpResponse<String> submit(String key, String body) throws Exception {
    keysUsed.add(key);
    return client.send(
        HttpRequest.newBuilder(uri(PATH))
            .header(HEADER, DEMO1)
            .header(IDEMPOTENCY_KEY, key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode awaitOverall(UUID id, String expected) {
    return await()
        .atMost(TIMEOUT)
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> get(PATH + "/" + id), s -> expected.equals(s.get("overallStatus").stringValue()));
  }

  private List<JsonNode> audit(UUID id) throws Exception {
    return get(PATH + "/" + id + "/audit").get("events").valueStream().toList();
  }

  private JsonNode get(String path) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(uri(path)).header(HEADER, DEMO1).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = json(response.body());
    if (body.has("deliveries")) {
      body.get("deliveries").forEach(d -> deliveriesSeen.add(d.get("deliveryId").stringValue()));
    }
    return body;
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  // JSON helpers

  private static JsonNode json(String body) {
    return JsonMapper.shared().readTree(body);
  }

  private static Map<String, UUID> notificationIds(HttpResponse<String> response) {
    return json(response.body())
        .get("notifications")
        .valueStream()
        .collect(
            Collectors.toMap(
                n -> n.get("recipientId").stringValue(),
                n -> UUID.fromString(n.get("notificationId").stringValue())));
  }

  private static List<String> channels(JsonNode status) {
    return status.get("deliveries").valueStream().map(d -> d.get("channel").stringValue()).toList();
  }

  private static Instant instant(JsonNode node, String field) {
    return Instant.parse(node.get(field).stringValue());
  }

  private static List<String> types(List<JsonNode> history) {
    return history.stream().map(e -> e.get("type").stringValue()).toList();
  }

  /** Event types for one channel, in history order. */
  private static List<String> typesOn(List<JsonNode> history, String channel) {
    return types(onChannel(history, channel));
  }

  private static List<String> detailsOf(List<JsonNode> history, String channel, String type) {
    return onChannel(history, channel).stream()
        .filter(e -> e.get("type").stringValue().equals(type))
        .map(e -> e.get("detail").stringValue())
        .toList();
  }

  private static List<JsonNode> onChannel(List<JsonNode> history, String channel) {
    return history.stream()
        .filter(e -> e.has("channel") && e.get("channel").stringValue().equals(channel))
        .toList();
  }
}
