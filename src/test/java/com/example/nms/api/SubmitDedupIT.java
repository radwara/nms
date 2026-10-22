package com.example.nms.api;

import static com.example.nms.support.SubmitRequests.IDEMPOTENCY_KEY;
import static com.example.nms.support.SubmitRequests.PATH;
import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.SubmitRequests.newKey;
import static com.example.nms.support.SubmitRequests.valid;
import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.HEADER;
import static com.example.nms.support.TestApiKeys.OTHER1;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.delivery.DeliveryWorker;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * #14, ADR-0006: a suppressed repeat over HTTP: per-item {@code SUPPRESSED} with {@code
 * duplicateOf} in the 202 body (also when every recipient is suppressed), replayed unchanged, and
 * visible through the status and audit APIs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubmitDedupIT {

  @Autowired private MockMvcTester mvc;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DeliveryWorker worker;

  private String event;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
    event = "evt-dedup-" + UUID.randomUUID();
  }

  @Test
  void mixedBatchMarksOnlyTheRepeatedRecipient() {
    MvcTestResult original = submit(newKey(), body("user-ok"));
    assertThat(original).hasStatus(202);
    assertThat(contentOf(original)).doesNotContain("duplicateOf");
    String originalId = items(original).getFirst().get("notificationId").stringValue();

    MvcTestResult mixed = submit(newKey(), body("user-email-only", "user-ok"));

    assertThat(mixed).hasStatus(202);
    List<JsonNode> items = items(mixed);
    assertThat(items.get(0).get("recipientId").stringValue()).isEqualTo("user-email-only");
    assertThat(items.get(0).get("status").stringValue()).isEqualTo("ACCEPTED");
    assertThat(items.get(0).has("duplicateOf")).isFalse();
    assertThat(items.get(1).get("recipientId").stringValue()).isEqualTo("user-ok");
    assertThat(items.get(1).get("status").stringValue()).isEqualTo("SUPPRESSED");
    assertThat(items.get(1).get("duplicateOf").stringValue()).isEqualTo(originalId);
  }

  @Test
  void fullySuppressedIs202AndItsReplayIs200WithTheSameBody() {
    assertThat(submit(newKey(), body("user-ok", "user-email-only"))).hasStatus(202);
    String key = newKey();
    String body = body("user-ok", "user-email-only");

    MvcTestResult suppressed = submit(key, body);
    MvcTestResult replay = submit(key, body);

    assertThat(suppressed).hasStatus(202);
    assertThat(items(suppressed)).allMatch(i -> i.get("status").stringValue().equals("SUPPRESSED"));
    assertThat(replay).hasStatus(200);
    assertThat(replay).bodyJson().isEqualTo(contentOf(suppressed));
  }

  @Test
  void statusAndAuditShowTheSuppressionOnBothNotifications() {
    UUID original = id(submit(newKey(), body("user-ok")));
    UUID repeat = id(submit(newKey(), body("user-ok")));
    worker.runOnce(); // sends the original's deliveries; the repeat has none

    JsonNode status = status(DEMO1, repeat);
    assertThat(status.get("overallStatus").stringValue()).isEqualTo("SUPPRESSED");
    assertThat(status.get("duplicateOf").stringValue()).isEqualTo(original.toString());
    assertThat(status.get("deliveries")).isEmpty();
    assertThat(status.get("duplicateSubmissions").asLong()).isZero();
    JsonNode originalStatus = status(DEMO1, original);
    assertThat(originalStatus.has("duplicateOf")).isTrue();
    assertThat(originalStatus.get("duplicateOf").isNull()).isTrue();
    assertThat(originalStatus.get("overallStatus").stringValue()).isNotEqualTo("SUPPRESSED");
    assertThat(originalStatus.get("deliveries")).hasSize(2);

    List<JsonNode> repeatEvents = events(repeat);
    assertThat(repeatEvents).hasSize(1);
    assertThat(repeatEvents.getFirst().get("type").stringValue()).isEqualTo("SUPPRESSED");
    assertThat(repeatEvents.getFirst().get("detail").stringValue())
        .isEqualTo("duplicate of " + original);
    assertThat(events(original))
        .filteredOn(e -> e.get("type").stringValue().equals("DUPLICATE_EVENT"))
        .singleElement()
        .satisfies(
            e -> assertThat(e.get("detail").stringValue()).isEqualTo("suppressed " + repeat));

    // Tenant scoping is unchanged: another source system cannot see the suppressed row.
    assertThat(mvc.get().uri(PATH + "/" + repeat).header(HEADER, OTHER1).exchange()).hasStatus(404);
  }

  @Test
  void anotherSourceSystemIsNeverSuppressedByThisOne() {
    assertThat(submit(newKey(), body("user-ok"))).hasStatus(202);

    MvcTestResult other =
        mvc.post()
            .uri(PATH)
            .header(HEADER, OTHER1)
            .header(IDEMPOTENCY_KEY, newKey())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("user-ok"))
            .exchange();

    assertThat(other).hasStatus(202);
    assertThat(items(other).getFirst().get("status").stringValue()).isEqualTo("ACCEPTED");
  }

  private String body(String... userIds) {
    ObjectNode body = valid(userIds);
    body.put("eventId", event);
    return body.toString();
  }

  private MvcTestResult submit(String key, String body) {
    return mvc.post()
        .uri(PATH)
        .header(HEADER, DEMO1)
        .header(IDEMPOTENCY_KEY, key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private List<JsonNode> items(MvcTestResult result) {
    JsonNode root = jsonMapper.readTree(contentOf(result));
    List<JsonNode> items = new ArrayList<>();
    root.get("notifications").forEach(items::add);
    return items;
  }

  private UUID id(MvcTestResult result) {
    assertThat(result).hasStatus(202);
    return UUID.fromString(items(result).getFirst().get("notificationId").stringValue());
  }

  private JsonNode status(String apiKey, UUID id) {
    MvcTestResult result = mvc.get().uri(PATH + "/" + id).header(HEADER, apiKey).exchange();
    assertThat(result).hasStatus(200);
    return jsonMapper.readTree(contentOf(result));
  }

  private List<JsonNode> events(UUID id) {
    MvcTestResult result =
        mvc.get().uri(PATH + "/" + id + "/audit").header(HEADER, DEMO1).exchange();
    assertThat(result).hasStatus(200);
    List<JsonNode> events = new ArrayList<>();
    jsonMapper.readTree(contentOf(result)).get("events").forEach(events::add);
    return events;
  }

  private static String contentOf(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
