package com.example.nms.intake;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.nms.support.DelegatingRoutingGate;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #14: {@code nms.notifications.submitted{outcome}} counts each notification of a committed new
 * submission; replays and rolled-back submissions count nothing. {@code nms.dedup.lookup} times one
 * query per new submission.
 */
@SpringBootTest
@Import(DelegatingRoutingGate.Configuration.class)
class IntakeMetricsIT {

  @Autowired private IntakeService intake;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry registry;
  @Autowired private DelegatingRoutingGate routing;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
    routing.failCommit(false);
  }

  @Test
  void countsAcceptedAndSuppressedNotificationsOnCommitOnly() {
    double accepted = count("accepted");
    double suppressed = count("suppressed");
    long lookups = lookups();

    intake.submit(IntakeServiceIT.command("user-ok"));
    SubmitCommand mixed = IntakeServiceIT.command("user-ok", "user-email-only");
    intake.submit(mixed);
    intake.submit(mixed); // replay: no lookup, nothing counted

    assertThat(count("accepted")).isEqualTo(accepted + 2);
    assertThat(count("suppressed")).isEqualTo(suppressed + 1);
    assertThat(lookups()).isEqualTo(lookups + 2);

    routing.failCommit(true);
    assertThatThrownBy(() -> intake.submit(IntakeServiceIT.command("user-sms-optout")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(count("accepted")).isEqualTo(accepted + 2);
    assertThat(count("suppressed")).isEqualTo(suppressed + 1);
  }

  private double count(String outcome) {
    return registry.get("nms.notifications.submitted").tag("outcome", outcome).counter().count();
  }

  private long lookups() {
    return registry.get("nms.dedup.lookup").timer().count();
  }
}
