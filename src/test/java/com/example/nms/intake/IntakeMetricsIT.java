package com.example.nms.intake;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #14: {@code nms.notifications.submitted{outcome}} counts each notification of a committed new
 * submission; replays and rolled-back submissions count nothing.
 */
@SpringBootTest
@Import(IntakeServiceIT.AuditingRoutingConfiguration.class)
class IntakeMetricsIT {

  @Autowired private IntakeService intake;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry registry;
  @Autowired private IntakeServiceIT.AuditingRoutingStep routing;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
    routing.fail = false;
  }

  @Test
  void countsAcceptedAndSuppressedNotificationsOnCommitOnly() {
    double accepted = count("accepted");
    double suppressed = count("suppressed");

    intake.submit(IntakeServiceIT.command("user-ok"));
    SubmitCommand mixed = IntakeServiceIT.command("user-ok", "user-email-only");
    intake.submit(mixed);
    intake.submit(mixed); // replay

    assertThat(count("accepted")).isEqualTo(accepted + 2);
    assertThat(count("suppressed")).isEqualTo(suppressed + 1);

    routing.fail = true;
    assertThatThrownBy(() -> intake.submit(IntakeServiceIT.command("user-sms-optout")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(count("accepted")).isEqualTo(accepted + 2);
    assertThat(count("suppressed")).isEqualTo(suppressed + 1);
  }

  private double count(String outcome) {
    return registry.get("nms.notifications.submitted").tag("outcome", outcome).counter().count();
  }
}
