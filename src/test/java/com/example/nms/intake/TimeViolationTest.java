package com.example.nms.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #8: time rules for new submissions (4221, 4222). */
class TimeViolationTest {

  private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

  @Test
  void futureExpiryAndEarlierScheduleAreValid() {
    assertThat(TimeViolation.of(command(NOW.plusSeconds(60), NOW.plusSeconds(120)), NOW)).isEmpty();
    assertThat(TimeViolation.of(command(null, null), NOW)).isEmpty();
  }

  @Test
  void pastScheduledAtIsValid() {
    assertThat(TimeViolation.of(command(NOW.minusSeconds(3600), NOW.plusSeconds(60)), NOW))
        .isEmpty();
  }

  @Test
  void expiresAtEqualToNowIsInThePast() {
    assertThat(TimeViolation.of(command(null, NOW), NOW))
        .containsExactly(TimeViolation.EXPIRES_AT_IN_PAST);
  }

  @Test
  void scheduledAtEqualToExpiresAtIsValid() {
    Instant expiresAt = NOW.plusSeconds(60);

    assertThat(TimeViolation.of(command(expiresAt, expiresAt), NOW)).isEmpty();
  }

  @Test
  void bothViolationsAreReportedTogether() {
    assertThat(TimeViolation.of(command(NOW.minusSeconds(1), NOW.minusSeconds(2)), NOW))
        .containsExactly(TimeViolation.EXPIRES_AT_IN_PAST, TimeViolation.SCHEDULED_AFTER_EXPIRY);
  }

  private static SubmitCommand command(Instant scheduledAt, Instant expiresAt) {
    return new SubmitCommand(
        "demo-app",
        "key",
        "a".repeat(64),
        "evt-1",
        "FRAUD_ALERT",
        Severity.INFO,
        Priority.NORMAL,
        List.of(new SubmitCommand.Recipient("user-ok", null)),
        "subject",
        "body",
        scheduledAt,
        expiresAt);
  }
}
