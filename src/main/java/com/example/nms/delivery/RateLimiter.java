package com.example.nms.delivery;

import com.example.nms.common.Channel;
import java.time.Instant;

/**
 * Outbound send limit per source system and channel, checked just before each claim (ADR-0007).
 * Safe for concurrent callers.
 */
interface RateLimiter {

  /** Allows every send. */
  RateLimiter ALLOW_ALL =
      new RateLimiter() {
        @Override
        public Decision acquire(Key key, Instant now) {
          return new Allowed(now);
        }

        @Override
        public void release(Key key, Instant windowStart) {}
      };

  /** Takes one send for {@code key} at {@code now}; an allowed send is counted. */
  Decision acquire(Key key, Instant now);

  /**
   * Gives back a send {@link Allowed} in the window starting at {@code windowStart} that never
   * happened (the claim was lost or threw). No effect once that window has passed.
   */
  void release(Key key, Instant windowStart);

  record Key(String sourceSystem, Channel channel) {}

  sealed interface Decision permits Allowed, Denied {}

  /** Counted in the window starting at {@code windowStart}. */
  record Allowed(Instant windowStart) implements Decision {}

  /** Over the limit; the key may send again at {@code until}, the window end. */
  record Denied(Instant until) implements Decision {}
}
