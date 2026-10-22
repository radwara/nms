package com.example.nms.delivery;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One counter per (source system, channel) for the current fixed window (ADR-0007). In memory: per
 * instance, reset on restart. One entry per key, replaced when its window passes, so memory is
 * bounded by source systems x channels. Safe for concurrent callers: each acquire and release is
 * one atomic {@code compute} on its key, so a window never allows more than the limit.
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
   * A caller whose {@code now} is at most one window before the key's current window (a concurrent
   * caller read the clock first) is counted in the current window. Further back (the clock was set
   * back) starts a new window at {@code now}.
   */
  @Override
  public Decision acquire(Key key, Instant now) {
    long nowNanos = epochNanos(now);
    long start = nowNanos - Math.floorMod(nowNanos, windowNanos);
    int limit = properties.limit(key.channel());
    Window window =
        windows.compute(
            key,
            (k, current) -> {
              if (current == null
                  || current.start() < start
                  || current.start() - nowNanos > windowNanos) {
                return new Window(start, 1, true);
              }
              if (current.count() >= limit) {
                return new Window(current.start(), current.count(), false);
              }
              return new Window(current.start(), current.count() + 1, true);
            });
    return window.allowed()
        ? new Allowed(instant(window.start()))
        : new Denied(instant(window.start() + windowNanos));
  }

  @Override
  public void release(Key key, Instant windowStart) {
    long start = epochNanos(windowStart);
    windows.computeIfPresent(
        key,
        (k, current) ->
            current.start() == start && current.count() > 0
                ? new Window(start, current.count() - 1, current.allowed())
                : current);
  }

  private static long epochNanos(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
  }

  private static Instant instant(long epochNanos) {
    return Instant.EPOCH.plusNanos(epochNanos);
  }

  /** Immutable: {@code allowed} is the outcome of the acquire that wrote it. */
  private record Window(long start, int count, boolean allowed) {}
}
