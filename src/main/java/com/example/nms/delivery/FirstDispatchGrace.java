package com.example.nms.delivery;

import com.example.nms.common.Channel;
import java.time.Duration;
import java.time.Instant;

/**
 * With the Kafka fast path on (ADR-0008), the poller takes {@code PENDING} rows of {@code channel}
 * only once they have been due longer than {@code duration}; until then they are left to the
 * consumer. Retries, lease reclaim and other channels are not affected.
 */
record FirstDispatchGrace(Channel channel, Duration duration) {

  /** Rows of {@code channel} due after this are left to the fast path. */
  Instant cutoff(Instant now) {
    return now.minus(duration);
  }
}
