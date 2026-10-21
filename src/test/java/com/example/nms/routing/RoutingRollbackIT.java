package com.example.nms.routing;

import static com.example.nms.support.ApiAssertions.assertSingleError;
import static com.example.nms.support.SubmitRequests.IDEMPOTENCY_KEY;
import static com.example.nms.support.SubmitRequests.PATH;
import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.SubmitRequests.newKey;
import static com.example.nms.support.SubmitRequests.valid;
import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.intake.Notification;
import com.example.nms.intake.RoutingStep;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
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

/**
 * #9: routing is part of the submit transaction. When it fails after the real router has written
 * its deliveries and audits, the whole submission rolls back, including the idempotency record, so
 * a retry with the same key is a new submission.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoutingRollbackIT.FailingRoutingConfiguration.class)
class RoutingRollbackIT {

  private static final List<String> TABLES =
      List.of("idempotency_record", "notification", "delivery", "audit_event");

  @Autowired private MockMvcTester mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FailAfterRouting routing;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
    routing.fail = false;
    routing.deliveriesWritten = -1;
  }

  @Test
  void routingFailureRollsBackTheSubmissionAndTheKeyCanBeRetried() {
    String key = newKey();
    String body = valid("user-ok", "user-email-only").toString();
    routing.fail = true;

    assertSingleError(submit(key, body), 500, 5000, null);

    // The real router ran: user-ok EMAIL + SMS, user-email-only EMAIL.
    assertThat(routing.deliveriesWritten).isEqualTo(3);
    for (String table : TABLES) {
      assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class))
          .as(table)
          .isZero();
    }

    routing.fail = false;
    assertThat(submit(key, body)).hasStatus(202);
    assertThat(jdbc.queryForObject("select count(*) from delivery", Integer.class)).isEqualTo(3);
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

  /**
   * Runs the real router, flushes its writes to the database inside the transaction, then fails
   * when told to.
   */
  static class FailAfterRouting implements RoutingStep {

    private final ChannelRouter router;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    volatile boolean fail;
    volatile int deliveriesWritten;

    FailAfterRouting(ChannelRouter router, EntityManager entityManager, JdbcTemplate jdbc) {
      this.router = router;
      this.entityManager = entityManager;
      this.jdbc = jdbc;
    }

    @Override
    public void route(List<Notification> notifications, Instant now) {
      router.route(notifications, now);
      if (fail) {
        entityManager.flush();
        Integer count = jdbc.queryForObject("select count(*) from delivery", Integer.class);
        deliveriesWritten = count == null ? 0 : count;
        throw new IllegalStateException("routing failed");
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FailingRoutingConfiguration {

    @Bean
    @Primary
    FailAfterRouting failAfterRouting(
        ChannelRouter router, EntityManager entityManager, JdbcTemplate jdbc) {
      return new FailAfterRouting(router, entityManager, jdbc);
    }
  }
}
