package com.example.nms.api;

import static com.example.nms.support.ApiAssertions.assertSingleError;
import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.TestApiKeys.SIM_EMAIL;
import static com.example.nms.support.TestApiKeys.SIM_SMS;
import static com.example.nms.support.TestApiKeys.WEBHOOK_HEADER;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.delivery.Delivery;
import com.example.nms.delivery.DeliveryRepository;
import com.example.nms.delivery.DeliveryWorker;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * #11: {@code POST /api/v1/webhooks/{provider}} binds and validates the generic callback body and
 * answers 200 for applied, ignored, and unknown callbacks. State rules are in {@code
 * DeliveryCallbacksIT}; token rules in {@code WebhookTokenAuthenticationIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderCallbackIT {

  private static final String EMAIL_HOOK = "/api/v1/webhooks/sim-email";

  @Autowired private MockMvcTester mvc;
  @Autowired private DeliveryWorker worker;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private Clock clock;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @ParameterizedTest(name = "{0} -> {1} {2}")
  @CsvSource(
      nullValues = "null",
      value = {
        "DELIVERED, DELIVERED, null",
        "BOUNCED, FAILED, BOUNCED",
        "FAILED, FAILED, PROVIDER_FAILED"
      })
  void callbackForASentDeliveryIs200AndApplied(String event, DeliveryStatus status, String reason) {
    Delivery sent = sent();

    MvcTestResult result = post(EMAIL_HOOK, SIM_EMAIL, body(sent.getId(), event).toString());

    assertThat(result).hasStatusOk();
    assertThat(result.getResponse().getContentLength()).isZero();
    Delivery done = deliveries.findById(sent.getId()).orElseThrow();
    assertThat(done.getStatus()).isEqualTo(status);
    assertThat(done.getFailureReason()).isEqualTo(reason);
  }

  @Test
  void repeatedCallbackIs200AndIgnored() {
    Delivery sent = sent();
    String body = body(sent.getId(), "DELIVERED").toString();

    assertThat(post(EMAIL_HOOK, SIM_EMAIL, body)).hasStatusOk();
    assertThat(post(EMAIL_HOOK, SIM_EMAIL, body)).hasStatusOk();

    assertThat(auditTypes(sent.getId())).containsExactly("SENT", "DELIVERED", "CALLBACK_IGNORED");
  }

  @Test
  void messageIdOfAnotherDeliveryIsNotCopiedAndTheOutcomeStillApplies() {
    Delivery other = sent();
    Notification n =
        notifications.save(notification("user-ok", Priority.HIGH, clock.instant(), null));
    Delivery sending = deliveries.save(delivery(n, Channel.EMAIL, clock.instant()));
    deliveries.claim(sending.getId(), DeliveryStatus.PENDING, 0, clock.instant());
    ObjectNode body = body(sending.getId(), "DELIVERED");
    body.put("providerMessageId", other.getProviderMessageId());

    assertThat(post(EMAIL_HOOK, SIM_EMAIL, body.toString())).hasStatusOk();

    Delivery done = deliveries.findById(sending.getId()).orElseThrow();
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(done.getProviderMessageId()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select detail from audit_event where type = 'DELIVERED' and delivery_id = ?",
                String.class,
                sending.getId()))
        .isEqualTo("message id conflict");
    Delivery untouched = deliveries.findById(other.getId()).orElseThrow();
    assertThat(untouched.getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(untouched.getProviderMessageId()).isEqualTo(other.getProviderMessageId());
    assertThat(untouched.getVersion()).isEqualTo(other.getVersion());
  }

  @Test
  void unknownDeliveryIdIs200() {
    MvcTestResult result =
        post(EMAIL_HOOK, SIM_EMAIL, body(UUID.randomUUID(), "DELIVERED").toString());

    assertThat(result).hasStatusOk();
    assertThat(jdbc.queryForObject("select count(*) from audit_event", Integer.class)).isZero();
  }

  @Test
  void callbackOnAnotherProvidersPathIsIgnoredWhateverTheBodySays() {
    Delivery sent = sent();
    ObjectNode body = body(sent.getId(), "FAILED");
    body.put("provider", "sim-email"); // unknown property: the token decides the provider

    assertThat(post("/api/v1/webhooks/sim-sms", SIM_SMS, body.toString())).hasStatusOk();

    assertThat(deliveries.findById(sent.getId()).orElseThrow().getStatus())
        .isEqualTo(DeliveryStatus.SENT);
    assertThat(
            jdbc.queryForObject(
                "select detail from audit_event where type = 'CALLBACK_IGNORED' and delivery_id = ?",
                String.class,
                sent.getId()))
        .isEqualTo("provider mismatch; event FAILED");
  }

  @Test
  void missingFieldsAreReportedTogether() {
    MvcTestResult result = post(EMAIL_HOOK, SIM_EMAIL, "{\"reasonCode\":null}");

    assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .containsExactly("deliveryId", "providerMessageId", "event", "occurredAt");
    assertThat(result).bodyJson().extractingPath("$.errors[*].code").asArray().containsOnly(4004);
  }

  @Test
  void invalidValuesAreReportedTogetherWithoutEchoingThem() throws Exception {
    ObjectNode body = JsonMapper.shared().createObjectNode();
    body.put("deliveryId", "1-1-1-1-1");
    body.put("providerMessageId", "m".repeat(129));
    body.put("event", "OPENED");
    body.put("reasonCode", "user@example.test");
    body.put("occurredAt", "yesterday");

    MvcTestResult result = post(EMAIL_HOOK, SIM_EMAIL, body.toString());

    assertThat(result).hasStatus(400);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .containsExactly("deliveryId", "providerMessageId", "event", "reasonCode", "occurredAt");
    assertThat(result).bodyJson().extractingPath("$.errors[*].code").asArray().containsOnly(4003);
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("1-1-1-1-1", "OPENED", "user@example.test", "yesterday");
  }

  @Test
  void wrongJsonTypesAre4003() {
    MvcTestResult result =
        post(
            EMAIL_HOOK,
            SIM_EMAIL,
            "{\"deliveryId\":1,\"providerMessageId\":[],\"event\":true,"
                + "\"reasonCode\":2,\"occurredAt\":3}");

    assertThat(result).hasStatus(400);
    assertThat(result).bodyJson().extractingPath("$.errors.length()").isEqualTo(5);
    assertThat(result).bodyJson().extractingPath("$.errors[*].code").asArray().containsOnly(4003);
  }

  @Test
  void bodyThatIsNotAnObjectIs4002() {
    assertSingleError(post(EMAIL_HOOK, SIM_EMAIL, "[]"), 400, 4002, null);
    assertSingleError(post(EMAIL_HOOK, SIM_EMAIL, "{"), 400, 4002, null);
  }

  @Test
  void callbackWithoutAValidTokenIs401AndChangesNothing() {
    Delivery sent = sent();
    String body = body(sent.getId(), "DELIVERED").toString();

    assertSingleError(
        mvc.post().uri(EMAIL_HOOK).contentType(MediaType.APPLICATION_JSON).content(body).exchange(),
        401,
        4011,
        WEBHOOK_HEADER);
    assertSingleError(post(EMAIL_HOOK, SIM_SMS, body), 401, 4012, WEBHOOK_HEADER);

    assertThat(deliveries.findById(sent.getId()).orElseThrow().getStatus())
        .isEqualTo(DeliveryStatus.SENT);
  }

  // Helpers

  private Delivery sent() {
    Notification n =
        notifications.save(notification("user-ok", Priority.HIGH, clock.instant(), null));
    Delivery queued = deliveries.save(delivery(n, Channel.EMAIL, clock.instant()));
    worker.runOnce();
    Delivery sent = deliveries.findById(queued.getId()).orElseThrow();
    assertThat(sent.getStatus()).isEqualTo(DeliveryStatus.SENT);
    return sent;
  }

  private static ObjectNode body(UUID deliveryId, String event) {
    ObjectNode body = JsonMapper.shared().createObjectNode();
    body.put("deliveryId", deliveryId.toString());
    body.put("providerMessageId", "em-123");
    body.put("event", event);
    if (!"DELIVERED".equals(event)) {
      body.put("reasonCode", "MAILBOX_UNAVAILABLE");
    }
    body.put("occurredAt", "2026-10-21T10:00:00Z");
    return body;
  }

  private MvcTestResult post(String path, String token, String body) {
    return mvc.post()
        .uri(path)
        .header(WEBHOOK_HEADER, token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private List<String> auditTypes(UUID deliveryId) {
    return jdbc.queryForList(
        "select type from audit_event where delivery_id = ? order by occurred_at, id",
        String.class,
        deliveryId);
  }
}
