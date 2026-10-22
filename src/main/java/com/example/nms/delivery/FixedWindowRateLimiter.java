package com.example.nms.delivery;

import com.example.nms.common.Channel;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One counter per (source system, channel) for the current fixed window (ADR-0007). In memory: per
 * instance, reset on restart. One entry per key, replaced when its window passes, so memory is
 * bounded by source systems x channels. Synchronized; the single poller is the only caller.
 */
final class FixedWindowRateLimiter implements RateLimiter {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private final RateLimitProperties properties;
  private final long windowNanos;
  private final Map<Key, Window> windows = new HashMap<>();

  FixedWindowRateLimiter(RateLimitProperties properties) {
    this.properties = properties;
    this.windowNanos = properties.window().toNanos();
  }

  @Override
  public synchronized Optional<Instant> acquire(String sourceSystem, Channel channel, Instant now) {
    long start = windowStart(now);
    Key key = new Key(sourceSystem, channel);
    Window window = windows.get(key);
    if (window == null || window.start != start) {
      window = new Window(start);
      windows.put(key, window);
    }
    if (window.count >= properties.limit(channel)) {
      return Optional.of(Instant.EPOCH.plusNanos(start + windowNanos));
    }
    window.count++;
    return Optional.empty();
  }

  /** Nanoseconds since the epoch at the start of the window holding {@code now}. */
  private long windowStart(Instant now) {
    long epochNanos =
        Math.addExact(Math.multiplyExact(now.getEpochSecond(), NANOS_PER_SECOND), now.getNano());
    return epochNanos - Math.floorMod(epochNanos, windowNanos);
  }

  private record Key(String sourceSystem, Channel channel) {}

  private static final class Window {

    private final long start;
    private int count;

    private Window(long start) {
      this.start = start;
    }
  }
}
