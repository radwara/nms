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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.node.ObjectNode;

/** #8: every submit rejection after authentication is audited as REJECTED with its codes. */
@SpringBootTest
@AutoConfigureMockMvc
class SubmitRejectionAuditIT {

  @Autowired private MockMvcTester mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void validationErrorsAreAuditedWithTheirDistinctCodes() {
    ObjectNode body = valid("user-ok");
    body.put("severity", "URGENT");
    body.put("priority", "SOON");
    body.put("subject", "SECRET-SUBJECT-" + "s".repeat(200));
    body.remove("eventId");

    MvcTestResult result = post(OTHER1, newKey(), body.toString());
    assertThat(result).hasStatus(400);

    assertThat(rejections())
        .containsExactly(
            Map.of(
                "source_system",
                "other-app",
                "detail",
                "codes 4004,4003; trace " + traceId(result)));
  }

  @Test
  void malformedJsonIsAudited() {
    MvcTestResult result = post(DEMO1, newKey(), "{\"eventId\": ");
    assertThat(result).hasStatus(400);

    assertThat(rejections())
        .containsExactly(
            Map.of("source_system", "demo-app", "detail", "codes 4002; trace " + traceId(result)));
  }

  @Test
  void keyConflictIsAudited() {
    String key = newKey();
    ObjectNode body = valid("user-ok");
    assertThat(post(DEMO1, key, body.toString())).hasStatus(202);
    body.put("subject", "Changed");

    MvcTestResult result = post(DEMO1, key, body.toString());
    assertThat(result).hasStatus(409);

    assertThat(rejections())
        .containsExactly(
            Map.of("source_system", "demo-app", "detail", "codes 4091; trace " + traceId(result)));
  }

  @Test
  void semanticErrorsAreAuditedAlthoughNothingWasPersisted() {
    MvcTestResult result = post(DEMO1, newKey(), valid("user-ok", "nobody").toString());
    assertThat(result).hasStatus(422);

    assertThat(rejections())
        .containsExactly(
            Map.of("source_system", "demo-app", "detail", "codes 4223; trace " + traceId(result)));
    assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from idempotency_record", Integer.class))
        .isZero();
  }

  @Test
  void unauthenticatedRequestsAreNotAudited() {
    assertThat(
            mvc.post()
                .uri(PATH)
                .header(IDEMPOTENCY_KEY, newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(valid("user-ok").toString()))
        .hasStatus(401);
    assertThat(post("nms_unknown_key", newKey(), valid("user-ok").toString())).hasStatus(401);

    assertThat(jdbc.queryForObject("select count(*) from audit_event", Integer.class)).isZero();
  }

  @Test
  void acceptedRequestsAreNotAuditedAsRejected() {
    assertThat(post(DEMO1, newKey(), valid("user-ok").toString())).hasStatus(202);

    assertThat(rejections()).isEmpty();
  }

  private MvcTestResult post(String apiKey, String idempotencyKey, String body) {
    return mvc.post()
        .uri(PATH)
        .header(HEADER, apiKey)
        .header(IDEMPOTENCY_KEY, idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private static String traceId(MvcTestResult result) {
    return result.getResponse().getHeader("X-Trace-Id");
  }

  /** REJECTED events: no notification, no delivery, no channel; source system and codes only. */
  private List<Map<String, Object>> rejections() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where type = 'REJECTED' and (notification_id is"
                    + " not null or delivery_id is not null or channel is not null)",
                Integer.class))
        .isZero();
    return jdbc.queryForList(
        "select source_system, detail from audit_event where type = 'REJECTED' order by id");
  }
}
