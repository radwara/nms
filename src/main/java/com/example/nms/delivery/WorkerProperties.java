package com.example.nms.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Delivery worker settings (ADR-0004, A-40).
 *
 * @param enabled whether the scheduled poller runs; tests turn it off and call the worker directly
 * @param pollInterval delay between the end of one poll and the start of the next
 * @param batchSize rows claimed per poll, best priority first
 * @param lease how long a {@code SENDING} claim is held before another poll may reclaim it; longer
 *     than the provider timeout
 * @param maxAttempts send attempts per delivery, including the first
 * @param backoffBase upper bound of the first retry delay; doubles per attempt (full jitter)
 */
@ConfigurationProperties("nms.worker")
record WorkerProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("500ms") Duration pollInterval,
    @DefaultValue("20") int batchSize,
    @DefaultValue("30s") Duration lease,
    @DefaultValue("4") int maxAttempts,
    @DefaultValue("2s") Duration backoffBase) {

  WorkerProperties {
    require(!pollInterval.isNegative() && !pollInterval.isZero(), "poll-interval must be > 0");
    require(batchSize > 0, "batch-size must be > 0");
    require(!lease.isNegative() && !lease.isZero(), "lease must be > 0");
    require(maxAttempts > 0, "max-attempts must be > 0");
    require(!backoffBase.isNegative(), "backoff-base must be >= 0");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException("nms.worker." + message);
    }
  }
}
