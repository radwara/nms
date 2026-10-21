package com.example.nms.api;

import static com.example.nms.support.ApiAssertions.assertSingleError;
import static com.example.nms.support.SubmitRequests.IDEMPOTENCY_KEY;
import static com.example.nms.support.SubmitRequests.PATH;
import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.SubmitRequests.newKey;
import static com.example.nms.support.SubmitRequests.valid;
import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** #8: submit validation; every error together, 400-class before 422-class, no values echoed. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SubmitValidationIT {

  @Autowired private MockMvcTester mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void missingIdempotencyKeyIs4001() {
    MvcTestResult result =
        mvc.post()
            .uri(PATH)
            .header(HEADER, DEMO1)
            .contentType(MediaType.APPLICATION_JSON)
            .content(valid("user-ok").toString())
            .exchange();

    assertSingleError(result, 400, 4001, IDEMPOTENCY_KEY);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"eventId", "type", "severity", "priority", "recipients", "subject", "body"})
  void missingRequiredFieldIs4004(String field) {
    ObjectNode body = valid("user-ok");
    body.remove(field);

    assertSingleError(submit(body), 400, 4004, field);
  }

  @Test
  void missingRecipientUserIdIs4004WithItsPath() {
    ObjectNode body = valid("user-ok");
    ((ArrayNode) body.get("recipients")).addObject().putArray("channels").add("EMAIL");

    assertSingleError(submit(body), 400, 4004, "recipients[1].userId");
  }

  @Test
  void unknownEnumValueIs4003() {
    ObjectNode body = valid("user-ok");
    body.put("severity", "URGENT");

    assertSingleError(submit(body), 400, 4003, "severity");
  }

  @Test
  void idempotencyKeyOver128CharactersIs4003() {
    assertSingleError(
        submit("k".repeat(129), valid("user-ok").toString()), 400, 4003, IDEMPOTENCY_KEY);
    assertThat(submit("k".repeat(128), valid("user-ok").toString())).hasStatus(202);
  }

  @Test
  void subjectAndBodyOverTheirLimitsAre4003() {
    ObjectNode body = valid("user-ok");
    body.put("subject", "s".repeat(201));
    body.put("body", "b".repeat(5001));

    MvcTestResult result = submit(body);

    assertThat(result).hasStatus(400);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].code")
        .asArray()
        .containsExactly(4003, 4003);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .containsExactly("subject", "body");
  }

  @Test
  void contentAtItsLimitsIsAccepted() {
    ObjectNode body = valid("user-ok");
    body.put("subject", "s".repeat(200));
    body.put("body", "b".repeat(5000));
    body.put("eventId", "e".repeat(128));
    body.put("type", "t".repeat(64));

    assertThat(submit(body)).hasStatus(202);
  }

  @Test
  void duplicateUserIdIs4003() {
    assertSingleError(
        submit(valid("user-ok", "user-email-only", "user-ok")), 400, 4003, "recipients[2].userId");
  }

  @Test
  void badEnumAndTimestampAreFieldErrorsReportedWithTheOthers() {
    ObjectNode body = valid("user-ok");
    body.put("priority", "SOON");
    body.put("expiresAt", "tomorrow");
    body.put("scheduledAt", 42);
    body.remove("eventId");

    MvcTestResult result = submit(body);

    assertThat(result).hasStatus(400);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .containsExactly("eventId", "priority", "scheduledAt", "expiresAt");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].code")
        .asArray()
        .containsExactly(4004, 4003, 4003, 4003);
  }

  @Test
  void moreThan100RecipientsIs4005() {
    String[] ids = new String[101];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = "user-" + i;
    }

    assertSingleError(submit(valid(ids)), 400, 4005, "recipients");
  }

  @Test
  void pastExpiresAtIs4221() {
    ObjectNode body = valid("user-ok");
    body.put("expiresAt", "2020-01-01T00:00:00Z");

    assertSingleError(submit(body), 422, 4221, "expiresAt");
  }

  @Test
  void scheduledAtAfterExpiresAtIs4222() {
    ObjectNode body = valid("user-ok");
    Instant expiresAt = Instant.now().plus(Duration.ofDays(1));
    body.put("expiresAt", expiresAt.toString());
    body.put("scheduledAt", expiresAt.plusSeconds(1).toString());

    assertSingleError(submit(body), 422, 4222, "scheduledAt");
  }

  @Test
  void timeAndRecipientErrorsAreReturnedTogetherAndNothingIsWritten() {
    ObjectNode body = valid("user-ok", "nobody");
    body.put("expiresAt", "2020-01-01T00:00:00Z");

    MvcTestResult result = submit(body);

    assertThat(result).hasStatus(422);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].code")
        .asArray()
        .containsExactly(4221, 4223);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .containsExactly("expiresAt", "recipients[1].userId");
    assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from idempotency_record", Integer.class))
        .isZero();
  }

  @Test
  void a400ErrorHidesThe422Errors() {
    ObjectNode body = valid("user-ok");
    body.remove("subject");
    body.put("expiresAt", "2020-01-01T00:00:00Z");

    assertSingleError(submit(body), 400, 4004, "subject");
  }

  @Test
  void everyErrorIsReturnedTogetherAndNoSubmittedValueIsEchoedOrLogged(CapturedOutput output) {
    ObjectNode body = valid("user-ok", "user-ok");
    body.put("eventId", "SECRET-EVENT-" + "e".repeat(128));
    body.put("subject", "SECRET-SUBJECT-" + "s".repeat(200));
    body.put("severity", "SECRET-SEVERITY");
    body.put("expiresAt", "SECRET-TIMESTAMP");

    MvcTestResult result =
        mvc.post()
            .uri(PATH)
            .header(HEADER, DEMO1)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.toString())
            .exchange();

    assertThat(result).hasStatus(400);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .containsExactly(
            IDEMPOTENCY_KEY, "eventId", "severity", "recipients[1].userId", "subject", "expiresAt");
    assertThat(result).bodyText().doesNotContain("SECRET", "user-ok");
    assertThat(output.getOut()).doesNotContain("SECRET", "user-ok");
  }

  @Test
  void bodyOver64KilobytesIs413WithTheStandardBody() {
    ObjectNode body = valid("user-ok");
    body.put("padding", "SECRET-PADDING-" + "p".repeat(1024 * 1024));

    MvcTestResult result = submit(body);

    assertSingleError(result, 413, 4130, null);
    assertThat(result).bodyText().doesNotContain("SECRET");
    assertThat(
            jdbc.queryForObject(
                "select detail from audit_event where type = 'REJECTED'", String.class))
        .startsWith("codes 4130");
  }

  @Test
  void nonObjectBodyIs4003() {
    assertSingleError(submit(newKey(), "[1, 2]"), 400, 4003, null);
  }

  private MvcTestResult submit(ObjectNode body) {
    return submit(newKey(), body.toString());
  }

  private MvcTestResult submit(String idempotencyKey, String body) {
    return mvc.post()
        .uri(PATH)
        .header(HEADER, DEMO1)
        .header(IDEMPOTENCY_KEY, idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }
}
