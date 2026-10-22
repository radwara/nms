package com.example.nms.delivery;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random source for the random half of the backoff (equal jitter); a bean so tests can make retry
 * times deterministic.
 */
@FunctionalInterface
interface Jitter {

  /** A duration in {@code [0, max]}, millisecond precision. */
  Duration upTo(Duration max);

  /** Uniform over {@code [0, max]}. */
  static Jitter uniform() {
    return max -> Duration.ofMillis(ThreadLocalRandom.current().nextLong(max.toMillis() + 1));
  }
}
