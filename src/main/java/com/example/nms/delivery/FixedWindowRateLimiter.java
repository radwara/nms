package com.example.nms.delivery;

import com.example.nms.common.Channel;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One counter per (source system, channel) for the current fixed window (ADR-0007). In memory: per
 * instance, reset on restart. One entry per key, replaced when its window passes, so memory is
 * bounded by source systems x channels. Safe for concurrent callers: each acquire is one atomic
 * {@code compute} on its key, so a window never allows more than the limit.
 */
final class FixedWindowRateLimiter implements RateLimiter {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private final RateLimitProperties properties;
  private final long windowNanos;
  private final ConcurrentMap<Key, Window> windows = new ConcurrentHashMap<>();

  FixedWindowRateLimiter(RateLimitProperties properties) {
    this.properties = properties;
    this.windowNanos = properties.window().toNanos();
  }

  /**
   * A caller whose {@code now} is older than the key's current window (a concurrent caller read the
   * clock first) is counted in the current window.
   */
  @Override
  public Optional<Instant> acquire(String sourceSystem, Channel channel, Instant now) {
    long start = windowStart(now);
    int limit = properties.limit(channel);
    Window window =
        windows.compute(
            new Key(sourceSystem, channel),
            (key, current) -> {
              if (current == null || current.start() < start) {
                return new Window(start, 1, true);
              }
              if (current.count() >= limit) {
                return current.allowed()
                    ? new Window(current.start(), current.count(), false)
                    : current;
              }
              return new Window(current.start(), current.count() + 1, true);
            });
    return window.allowed()
        ? Optional.empty()
        : Optional.of(Instant.EPOCH.plusNanos(window.start() + windowNanos));
  }

  /** Nanoseconds since the epoch at the start of the window holding {@code now}. */
  private long windowStart(Instant now) {
    long epochNanos =
        Math.addExact(Math.multiplyExact(now.getEpochSecond(), NANOS_PER_SECOND), now.getNano());
    return epochNanos - Math.floorMod(epochNanos, windowNanos);
  }

  private record Key(String sourceSystem, Channel channel) {}

  /** Immutable: {@code allowed} is the outcome of the acquire that wrote it. */
  private record Window(long start, int count, boolean allowed) {}
}
