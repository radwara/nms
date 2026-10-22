package com.example.nms.intake;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Intake metrics (architecture.md "Operations"). {@code nms.notifications.submitted{outcome}}
 * counts the notifications of new submissions once committed; replays are not counted. {@code
 * nms.dedup.lookup} times the dedup query. No event or type tags: they are caller-defined, so
 * unbounded.
 */
@Component
class IntakeMetrics {

  private final Counter accepted;
  private final Counter suppressed;
  private final Timer dedupLookup;

  IntakeMetrics(MeterRegistry registry) {
    accepted = submitted(registry, "accepted");
    suppressed = submitted(registry, "suppressed");
    dedupLookup =
        Timer.builder("nms.dedup.lookup")
            .description("Event dedup query, one per new submission while dedup is enabled")
            .register(registry);
  }

  <T> T timeDedupLookup(Supplier<T> lookup) {
    return dedupLookup.record(lookup);
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
