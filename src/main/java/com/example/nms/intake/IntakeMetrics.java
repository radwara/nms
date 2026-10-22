package com.example.nms.intake;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Intake metrics (architecture.md "Operations"). {@code nms.notifications.submitted{outcome}}
 * counts the notifications of new submissions once committed; replays are not counted. No event or
 * type tags: they are caller-defined, so unbounded.
 */
@Component
class IntakeMetrics {

  private final Counter accepted;
  private final Counter suppressed;

  IntakeMetrics(MeterRegistry registry) {
    accepted = submitted(registry, "accepted");
    suppressed = submitted(registry, "suppressed");
  }

  /** One committed submission's notifications by outcome. */
  void submitted(int acceptedCount, int suppressedCount) {
    accepted.increment(acceptedCount);
    suppressed.increment(suppressedCount);
  }

  /** Registered up front, so the suppression ratio has a denominator from the first scrape. */
  private static Counter submitted(MeterRegistry registry, String outcome) {
    return Counter.builder("nms.notifications.submitted")
        .description("Notifications of new submissions by outcome (accepted or suppressed)")
        .tag("outcome", outcome)
        .register(registry);
  }
}
