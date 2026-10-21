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

import com.example.nms.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.node.ObjectNode;

/**
 * #8: the idempotency lookup runs before the time rules, so a retry of an accepted request is
 * answered after its {@code expiresAt} has passed; the rules still apply to new submissions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SubmitReplayAfterExpiryIT.ClockConfiguration.class)
class SubmitReplayAfterExpiryIT {

  @Autowired private MockMvcTester mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MutableClock clock;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void sameKeyAndPayloadReplaysAfterExpiresAtHasPassed() throws Exception {
    String key = newKey();
    String body = expiringInOneHour().toString();
    MvcTestResult original = submit(key, body);
    assertThat(original).hasStatus(202);

    clock.advance(Duration.ofHours(2));
    MvcTestResult replay = submit(key, body);

    assertThat(replay).hasStatus(200);
    assertThat(replay).bodyJson().isEqualTo(original.getResponse().getContentAsString());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where type = 'DUPLICATE_SUBMISSION'",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void sameKeyWithADifferentPayloadIs409NotATimeError() {
    String key = newKey();
    ObjectNode body = expiringInOneHour();
    assertThat(submit(key, body.toString())).hasStatus(202);

    clock.advance(Duration.ofHours(2));
    body.put("subject", "Changed");

    assertSingleError(submit(key, body.toString()), 409, 4091, IDEMPOTENCY_KEY);
  }

  @Test
  void aNewSubmissionWithThePastExpiresAtIs4221AndWritesNothing() {
    String body = expiringInOneHour().toString();
    clock.advance(Duration.ofHours(2));

    assertSingleError(submit(newKey(), body), 422, 4221, "expiresAt");
    assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from idempotency_record", Integer.class))
        .isZero();
  }

  private ObjectNode expiringInOneHour() {
    ObjectNode body = valid("user-ok", "user-email-only");
    body.put("expiresAt", clock.instant().plus(Duration.ofHours(1)).toString());
    return body;
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

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
  }
}
