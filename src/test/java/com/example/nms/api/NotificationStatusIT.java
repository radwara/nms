package com.example.nms.api;

import static com.example.nms.support.ApiAssertions.assertSingleError;
import static com.example.nms.support.SubmitRequests.IDEMPOTENCY_KEY;
import static com.example.nms.support.SubmitRequests.PATH;
import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.SubmitRequests.newKey;
import static com.example.nms.support.SubmitRequests.valid;
import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.DEMO2;
import static com.example.nms.support.TestApiKeys.HEADER;
import static com.example.nms.support.TestApiKeys.OTHER1;
import static com.example.nms.support.TestApiKeys.SIM_EMAIL;
import static com.example.nms.support.TestApiKeys.SIM_SMS;
import static com.example.nms.support.TestApiKeys.WEBHOOK_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.delivery.Delivery;
import com.example.nms.delivery.DeliveryRepository;
import com.example.nms.delivery.DeliveryWorker;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * #11: {@code GET /notifications/{id}} and {@code /audit} (FR-2.1 - FR-2.4, FR-4.3, FR-6.1),
 * tenant-scoped, through submit, worker, and callbacks.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationStatusIT {

  @Autowired private MockMvcTester mvc;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private DeliveryWorker worker;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void statusFollowsTheDeliveriesFromQueuedToPartiallyDelivered() {
    String key = newKey();
    String body = valid("user-ok").toString();
    UUID id = submit(DEMO1, key, body);
    assertThat(submit(DEMO2, key, body)).as("replay").isEqualTo(id);

    JsonNode queued = status(id);
    Notification n = notifications.findById(id).orElseThrow();
    assertThat(queued.get("notificationId").stringValue()).isEqualTo(id.toString());
    assertThat(queued.get("recipientId").stringValue()).isEqualTo("user-ok");
    assertThat(queued.get("eventId").stringValue()).isEqualTo("evt-1");
    assertThat(queued.get("type").stringValue()).isEqualTo("FRAUD_ALERT");
    assertThat(queued.get("severity").stringValue()).isEqualTo("CRITICAL");
    assertThat(queued.get("priority").stringValue()).isEqualTo("HIGH");
    assertThat(queued.get("requestedChannels").isNull()).as("preferences").isTrue();
    assertThat(queued.get("overallStatus").stringValue()).isEqualTo("IN_PROGRESS");
    assertThat(queued.get("duplicateSubmissions").asLong()).isEqualTo(1);
    assertThat(instant(queued, "createdAt")).isEqualTo(micros(n.getCreatedAt()));
    assertThat(queued.get("scheduledAt").isNull()).isTrue();
    assertThat(instant(queued, "expiresAt")).isEqualTo(micros(n.getExpiresAt()));
    assertThat(channels(queued)).containsExactly("EMAIL", "SMS");
    for (JsonNode d : queued.get("deliveries")) {
      assertThat(d.get("status").stringValue()).isEqualTo("PENDING");
      assertThat(d.get("attempts").asInt()).isZero();
      assertThat(d.has("reason") || d.has("sentAt") || d.has("finalizedAt")).isFalse();
    }

    worker.runOnce();
    JsonNode sent = status(id);
    assertThat(sent.get("overallStatus").stringValue()).isEqualTo("AWAITING_CONFIRMATION");
    UUID email = deliveryId(sent, 0);
    UUID sms = deliveryId(sent, 1);
    for (int i = 0; i < 2; i++) {
      JsonNode d = sent.get("deliveries").get(i);
      Delivery row = deliveries.findById(deliveryId(sent, i)).orElseThrow();
      assertThat(d.get("status").stringValue()).isEqualTo("SENT");
      assertThat(d.get("attempts").asInt()).isEqualTo(1);
      assertThat(instant(d, "sentAt")).isEqualTo(micros(row.getSentAt()));
      assertThat(d.has("finalizedAt")).isFalse();
    }

    callback("sim-email", SIM_EMAIL, email, "DELIVERED");
    assertThat(status(id).get("overallStatus").stringValue()).isEqualTo("AWAITING_CONFIRMATION");

    callback("sim-sms", SIM_SMS, sms, "FAILED");
    JsonNode done = status(id);
    assertThat(done.get("overallStatus").stringValue()).isEqualTo("PARTIALLY_DELIVERED");
    JsonNode emailState = done.get("deliveries").get(0);
    JsonNode smsState = done.get("deliveries").get(1);
    assertThat(emailState.get("status").stringValue()).isEqualTo("DELIVERED");
    assertThat(emailState.has("reason")).isFalse();
    assertThat(instant(emailState, "finalizedAt"))
        .isEqualTo(micros(deliveries.findById(email).orElseThrow().getFinalizedAt()));
    assertThat(smsState.get("status").stringValue()).isEqualTo("FAILED");
    assertThat(smsState.get("reason").stringValue()).isEqualTo("CALLBACK_FAILED");
    assertThat(instant(smsState, "finalizedAt"))
        .isEqualTo(micros(deliveries.findById(sms).orElseThrow().getFinalizedAt()));
  }

  @Test
  void requestedChannelsAreShownAndANotificationWithNoUsableChannelIsNotRouted() {
    ObjectNode body = valid("user-email-only");
    ((ObjectNode) body.get("recipients").get(0)).putArray("channels").add("SMS");

    JsonNode status = status(submit(DEMO1, newKey(), body.toString()));

    assertThat(status.get("requestedChannels").toString()).isEqualTo("[\"SMS\"]");
    assertThat(status.get("overallStatus").stringValue()).isEqualTo("NOT_ROUTED");
    assertThat(status.get("deliveries").isEmpty()).isTrue();
  }

  @Test
  void notificationBeforeItsScheduledAtIsScheduled() {
    Instant scheduledAt = Instant.now().truncatedTo(ChronoUnit.MICROS).plus(Duration.ofHours(1));
    ObjectNode body = valid("user-email-only");
    body.put("scheduledAt", scheduledAt.toString());

    JsonNode status = status(submit(DEMO1, newKey(), body.toString()));

    assertThat(status.get("overallStatus").stringValue()).isEqualTo("SCHEDULED");
    assertThat(instant(status, "scheduledAt")).isEqualTo(scheduledAt);
  }

  @Test
  void auditIsReturnedInOrderWithCodesOnly() throws Exception {
    String key = newKey();
    String body = valid("user-ok").toString();
    UUID id = submit(DEMO1, key, body);
    submit(DEMO1, key, body);
    worker.runOnce();
    JsonNode status = status(id);
    callback("sim-email", SIM_EMAIL, deliveryId(status, 0), "BOUNCED");
    callback("sim-email", SIM_EMAIL, deliveryId(status, 0), "DELIVERED");

    MvcTestResult result = get(DEMO1, PATH + "/" + id + "/audit");

    assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
    JsonNode events = jsonMapper.readTree(result.getResponse().getContentAsString()).get("events");
    List<String> lines = new ArrayList<>();
    for (JsonNode e : events) {
      lines.add(
          e.get("type").stringValue()
              + (e.has("channel") ? " " + e.get("channel").stringValue() : "")
              + (e.has("detail") ? " " + e.get("detail").stringValue() : ""));
    }
    assertThat(lines.subList(0, 6))
        .containsExactly(
            "ACCEPTED",
            "ROUTED EMAIL PREFERENCE",
            "ROUTED SMS PREFERENCE",
            "QUEUED EMAIL",
            "QUEUED SMS",
            "DUPLICATE_SUBMISSION same Idempotency-Key; original returned");
    // Equal priority and due time: the worker's send order between the two is not defined.
    assertThat(lines.subList(6, 8)).containsExactlyInAnyOrder("SENT EMAIL", "SENT SMS");
    assertThat(lines.subList(8, lines.size()))
        .containsExactly(
            "FAILED EMAIL BOUNCED, code MAILBOX_UNAVAILABLE",
            "CALLBACK_IGNORED EMAIL already FAILED; event DELIVERED");
    // The submit transaction's events share one timestamp; the id keeps their insert order.
    assertThat(events.get(0).get("at")).isEqualTo(events.get(4).get("at"));
    for (int i = 1; i < events.size(); i++) {
      assertThat(instant(events.get(i), "at")).isAfterOrEqualTo(instant(events.get(i - 1), "at"));
    }
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("demo-app", "Suspicious login", "sim.test", "+1555");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/audit"})
  void unknownMalformedAndOtherTenantsIdsAreAll4041(String suffix) {
    UUID id = submit(DEMO1, newKey(), valid("user-ok").toString());
    String upper = id.toString().toUpperCase(Locale.ROOT);

    assertThat(get(DEMO2, PATH + "/" + upper + suffix)).as("canonical, upper case").hasStatusOk();
    for (String other :
        List.of(
            UUID.randomUUID().toString(),
            "not-a-uuid",
            "1-1-1-1-1",
            id.toString().replace("-", ""),
            id + "0")) {
      assertSingleError(get(DEMO1, PATH + "/" + other + suffix), 404, 4041, null);
    }
    assertSingleError(get(OTHER1, PATH + "/" + id + suffix), 404, 4041, null);
  }

  // Helpers

  private UUID submit(String apiKey, String key, String body) {
    MvcTestResult result =
        mvc.post()
            .uri(PATH)
            .header(HEADER, apiKey)
            .header(IDEMPOTENCY_KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .exchange();
    assertThat(result.getResponse().getStatus()).isIn(200, 202);
    return UUID.fromString(
        read(result).get("notifications").get(0).get("notificationId").stringValue());
  }

  private JsonNode status(UUID id) {
    MvcTestResult result = get(DEMO1, PATH + "/" + id);
    assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
    return read(result);
  }

  private void callback(String provider, String token, UUID deliveryId, String event) {
    ObjectNode body = jsonMapper.createObjectNode();
    body.put("deliveryId", deliveryId.toString());
    body.put("providerMessageId", "msg-1");
    body.put("event", event);
    if (!"DELIVERED".equals(event)) {
      body.put("reasonCode", "MAILBOX_UNAVAILABLE");
    }
    body.put("occurredAt", Instant.now().toString());
    assertThat(
            mvc.post()
                .uri("/api/v1/webhooks/" + provider)
                .header(WEBHOOK_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .hasStatusOk();
  }

  private MvcTestResult get(String apiKey, String path) {
    return mvc.get().uri(path).header(HEADER, apiKey).exchange();
  }

  private JsonNode read(MvcTestResult result) {
    try {
      return jsonMapper.readTree(result.getResponse().getContentAsString());
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> channels(JsonNode status) {
    List<String> channels = new ArrayList<>();
    status.get("deliveries").forEach(d -> channels.add(d.get("channel").stringValue()));
    return channels;
  }

  private static UUID deliveryId(JsonNode status, int index) {
    JsonNode d = status.get("deliveries").get(index);
    assertThat(d.get("channel").stringValue()).isEqualTo(Channel.values()[index].name());
    return UUID.fromString(d.get("deliveryId").stringValue());
  }

  private static Instant instant(JsonNode node, String field) {
    return Instant.parse(node.get(field).stringValue());
  }

  private static Instant micros(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }
}
