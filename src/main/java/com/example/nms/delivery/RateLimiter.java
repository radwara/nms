package com.example.nms.delivery;

import com.example.nms.common.Channel;
import java.time.Instant;
import java.util.Optional;

/** Outbound send limit per source system and channel, checked just before each claim (ADR-0007). */
interface RateLimiter {

  /** Allows every send. */
  RateLimiter ALLOW_ALL = (sourceSystem, channel, now) -> Optional.empty();

  /**
   * Takes one send for {@code (sourceSystem, channel)} at {@code now}.
   *
   * @return empty if the send may go ahead (it is counted), else when the row may be tried again
   */
  Optional<Instant> acquire(String sourceSystem, Channel channel, Instant now);
}
