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
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.intake.IdempotencyRecord;
import com.example.nms.intake.IdempotencyRecordRepository;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** #8: submit, split per recipient, idempotent replay, key conflicts, and key expiry. */
@SpringBootTest
@AutoConfigureMockMvc
class SubmitNotificationIT {

  @Autowired private MockMvcTester mvc;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdempotencyRecordRepository records;
  @Autowired private NotificationRepository notifications;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void validRequestIs202WithOneNotificationPerRecipientEachAuditedAccepted() {
    ObjectNode body = valid("user-ok", "user-email-only", "user-sms-optout");
    ((ObjectNode) body.get("recipients").get(0)).putArray("channels").add("SMS").add("EMAIL");
    body.put("sourceSystem", "spoofed-system");

    MvcTestResult result = submit(DEMO1, newKey(), body.toString());

    assertThat(result).hasStatus(202).hasContentType(MediaType.APPLICATION_JSON);
    List<Map.Entry<String, UUID>> ids = ids(result);
    assertThat(ids)
        .extracting(Map.Entry::getKey)
        .containsExactly("user-ok", "user-email-only", "user-sms-optout");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.notifications[*].status")
        .asArray()
        .containsOnly("ACCEPTED");

    List<Notification> stored =
        notifications.findAllById(ids.stream().map(Map.Entry::getValue).toList());
    assertThat(stored).hasSize(3);
    assertThat(stored)
        .extracting(Notification::getSubmissionId)
        .containsOnly(stored.getFirst().getSubmissionId());
    Notification first = notifications.findById(ids.getFirst().getValue()).orElseThrow();
    assertThat(first.getSourceSystem()).isEqualTo("demo-app");
    assertThat(first.getEventId()).isEqualTo("evt-1");
    assertThat(first.getType()).isEqualTo("FRAUD_ALERT");
    assertThat(first.getSeverity()).isEqualTo(Severity.CRITICAL);
    assertThat(first.getPriority()).isEqualTo(Priority.HIGH);
    assertThat(first.getRequestedChannels()).containsExactly(Channel.SMS, Channel.EMAIL);
    assertThat(first.getScheduledAt()).isNull();
    assertThat(first.getExpiresAt()).isEqualTo(Instant.parse(body.get("expiresAt").stringValue()));
    assertThat(notifications.findById(ids.get(1).getValue()).orElseThrow().getRequestedChannels())
        .isNull();

    for (Map.Entry<String, UUID> id : ids) {
      assertThat(auditTypes(id.getValue())).containsExactly("ACCEPTED");
    }
    assertThat(jdbc.queryForObject("select count(*) from delivery", Integer.class)).isZero();
  }

  @Test
  void sameKeyAndPayloadIs200WithTheSameIdsAndAuditsDuplicateOnEach() {
    String key = newKey();
    String body = valid("user-ok", "user-email-only").toString();
    List<Map.Entry<String, UUID>> original = ids(submit(DEMO1, key, body));

    MvcTestResult replay = submit(DEMO2, key, body); // other key of the same source system

    assertThat(replay).hasStatus(200);
    assertThat(ids(replay)).isEqualTo(original);
    assertThat(notifications.count()).isEqualTo(2);
    for (Map.Entry<String, UUID> id : original) {
      assertThat(auditTypes(id.getValue())).containsExactly("ACCEPTED", "DUPLICATE_SUBMISSION");
    }
  }

  @Test
  void replayIgnoresWhitespaceAndKeyOrder() {
    String key = newKey();
    ObjectNode body = valid("user-ok", "user-email-only");
    List<Map.Entry<String, UUID>> original = ids(submit(DEMO1, key, body.toString()));

    String reordered =
        jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(reversedKeys(body));
    assertThat(reordered).isNotEqualTo(body.toString());

    MvcTestResult replay = submit(DEMO1, key, reordered);

    assertThat(replay).hasStatus(200);
    assertThat(ids(replay)).isEqualTo(original);
  }

  @Test
  void replayIgnoresUnknownProperties() {
    String key = newKey();
    ObjectNode body = valid("user-ok");
    body.put("sourceSystem", "first");
    List<Map.Entry<String, UUID>> original = ids(submit(DEMO1, key, body.toString()));
    body.put("sourceSystem", "second");
    body.put("unknownField", 42);

    MvcTestResult replay = submit(DEMO1, key, body.toString());

    assertThat(replay).hasStatus(200);
    assertThat(ids(replay)).isEqualTo(original);
  }

  @Test
  void replayTreatsNullAndAbsentOptionalFieldsAlike() {
    String key = newKey();
    ObjectNode body = valid("user-ok");
    body.putNull("scheduledAt");
    ((ObjectNode) body.get("recipients").get(0)).putNull("channels");
    List<Map.Entry<String, UUID>> original = ids(submit(DEMO1, key, body.toString()));
    body.remove("scheduledAt");
    ((ObjectNode) body.get("recipients").get(0)).remove("channels");

    MvcTestResult replay = submit(DEMO1, key, body.toString());

    assertThat(replay).hasStatus(200);
    assertThat(ids(replay)).isEqualTo(original);
  }

  @Test
  void replayTreatsTheSameInstantInAnotherOffsetAlike() {
    String key = newKey();
    ObjectNode body = valid("user-ok");
    body.put("expiresAt", "2099-01-01T12:00:00Z");
    List<Map.Entry<String, UUID>> original = ids(submit(DEMO1, key, body.toString()));
    body.put("expiresAt", "2099-01-01T14:00:00.000+02:00");

    MvcTestResult replay = submit(DEMO1, key, body.toString());

    assertThat(replay).hasStatus(200);
    assertThat(ids(replay)).isEqualTo(original);
  }

  @Test
  void replayListsNotificationsInTheRequestsRecipientOrder() {
    String key = newKey();
    String body =
        valid(
                "user-silent",
                "user-bounce",
                "user-ok",
                "user-reject",
                "user-cb-failed",
                "user-flaky2")
            .toString();
    submit(DEMO1, key, body);

    MvcTestResult replay = submit(DEMO1, key, body);

    assertThat(ids(replay))
        .extracting(Map.Entry::getKey)
        .containsExactly(
            "user-silent",
            "user-bounce",
            "user-ok",
            "user-reject",
            "user-cb-failed",
            "user-flaky2");
  }

  @Test
  void reorderedRecipientsAreADifferentPayload() {
    String key = newKey();
    submit(DEMO1, key, valid("user-ok", "user-email-only").toString());

    MvcTestResult result = submit(DEMO1, key, valid("user-email-only", "user-ok").toString());

    assertSingleError(result, 409, 4091, IDEMPOTENCY_KEY);
    assertThat(notifications.count()).isEqualTo(2);
  }

  @Test
  void sameKeyWithADifferentPayloadIs409() {
    String key = newKey();
    ObjectNode body = valid("user-ok");
    submit(DEMO1, key, body.toString());
    body.put("subject", "Another subject");

    MvcTestResult result = submit(DEMO1, key, body.toString());

    assertSingleError(result, 409, 4091, IDEMPOTENCY_KEY);
    assertThat(notifications.count()).isEqualTo(1);
  }

  @Test
  void keysAreScopedPerSourceSystem() {
    String key = newKey();
    String body = valid("user-ok").toString();
    List<Map.Entry<String, UUID>> demo = ids(submit(DEMO1, key, body));

    MvcTestResult other = submit(OTHER1, key, body);

    assertThat(other).hasStatus(202);
    assertThat(ids(other)).doesNotContainAnyElementsOf(demo);
  }

  @Test
  void expiredKeyCreatesANewSubmissionAndReplacesTheRecordInPlace() {
    String key = newKey();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    IdempotencyRecord expired =
        records.saveAndFlush(
            new IdempotencyRecord(
                "demo-app",
                key,
                "a".repeat(64),
                UUID.randomUUID(),
                now.minus(Duration.ofDays(8)),
                now.minus(Duration.ofDays(1))));

    MvcTestResult result = submit(DEMO1, key, valid("user-ok").toString());

    assertThat(result).hasStatus(202);
    Notification created = notifications.findById(ids(result).getFirst().getValue()).orElseThrow();
    List<IdempotencyRecord> forKey =
        records.findAll().stream().filter(r -> r.getIdempotencyKey().equals(key)).toList();
    assertThat(forKey).hasSize(1);
    IdempotencyRecord replaced = forKey.getFirst();
    assertThat(replaced.getId()).isEqualTo(expired.getId());
    assertThat(replaced.getSubmissionId()).isEqualTo(created.getSubmissionId());
    assertThat(replaced.getCreatedAt()).isEqualTo(created.getCreatedAt());
    assertThat(Duration.between(replaced.getCreatedAt(), replaced.getExpiresAt()))
        .isEqualTo(Duration.ofDays(7));
  }

  @Test
  void unknownRecipientsAre422ListingEveryOneAndNothingIsPersisted() {
    String key = newKey();

    MvcTestResult result =
        submit(
            DEMO1, key, valid("user-ok", "nobody-one", "user-email-only", "nobody-two").toString());

    assertThat(result).hasStatus(422).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].code")
        .asArray()
        .containsExactly(4223, 4223);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .containsExactly("recipients[1].userId", "recipients[3].userId");
    assertThat(result).bodyText().doesNotContain("nobody");
    assertThat(notifications.count()).isZero();
    assertThat(records.count()).isZero();

    // Rejections are not stored: a corrected retry with the same key succeeds.
    assertThat(submit(DEMO1, key, valid("user-ok", "user-email-only").toString())).hasStatus(202);
  }

  @Test
  void pastScheduledAtIsAccepted() {
    ObjectNode body = valid("user-ok");
    body.put("scheduledAt", "2020-01-01T00:00:00+02:00");

    MvcTestResult result = submit(DEMO1, newKey(), body.toString());

    assertThat(result).hasStatus(202);
    assertThat(
            notifications
                .findById(ids(result).getFirst().getValue())
                .orElseThrow()
                .getScheduledAt())
        .isEqualTo(Instant.parse("2019-12-31T22:00:00Z"));
  }

  private MvcTestResult submit(String apiKey, String idempotencyKey, String body) {
    return mvc.post()
        .uri(PATH)
        .header(HEADER, apiKey)
        .header(IDEMPOTENCY_KEY, idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  /** (recipientId, notificationId) pairs in response order. */
  private List<Map.Entry<String, UUID>> ids(MvcTestResult result) {
    JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    List<Map.Entry<String, UUID>> ids = new ArrayList<>();
    body.get("notifications")
        .values()
        .forEach(
            n ->
                ids.add(
                    Map.entry(
                        n.get("recipientId").stringValue(),
                        UUID.fromString(n.get("notificationId").stringValue()))));
    return ids;
  }

  private List<String> auditTypes(UUID notificationId) {
    return jdbc.queryForList(
        "select type from audit_event where notification_id = ? order by id",
        String.class,
        notificationId);
  }

  private static JsonNode reversedKeys(JsonNode node) {
    if (node.isObject()) {
      List<String> names = new ArrayList<>(node.propertyNames());
      Collections.reverse(names);
      ObjectNode copy = JsonMapper.shared().createObjectNode();
      names.forEach(name -> copy.set(name, reversedKeys(node.get(name))));
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = JsonMapper.shared().createArrayNode();
      node.values().forEach(element -> copy.add(reversedKeys(element)));
      return copy;
    }
    return node;
  }
}
