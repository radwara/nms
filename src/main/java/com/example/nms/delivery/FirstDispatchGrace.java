package com.example.nms.delivery;

import com.example.nms.common.Channel;
import java.time.Duration;
import java.time.Instant;

/**
 * With the Kafka fast path on (ADR-0008), the poller takes a {@code PENDING} row of {@code channel}
 * only once it was created more than {@code duration} ago, or was moved since (postpone, rate-limit
 * deferral); until then it is left to the consumer. Retries, lease reclaim and other channels are
 * not affected.
 */
record FirstDispatchGrace(Channel channel, Duration duration) {

  /** Unmoved rows of {@code channel} created after this are left to the fast path. */
  Instant cutoff(Instant now) {
    return now.minus(duration);
  }
}
