package com.example.nms.delivery;

import com.example.nms.provider.SendResult.Failed;
import java.time.Duration;
import java.time.Instant;

/**
 * What follows a failed attempt (A-40 - A-44): retryable categories back off exponentially with
 * full jitter, {@code delay = random(0, base * 2^(attempt - 1))}; {@code RATE_LIMITED} waits for
 * {@code Retry-After} when given, clamped to {@code [0, 1h]}. Every category counts toward the
 * attempt budget. A retry that would fall at or after {@code expiresAt} expires instead.
 */
final class RetryPolicy {

  static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";

  /** Upper bound for a vendor's {@code Retry-After}, so one response cannot park a delivery. */
  static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

  /** Least a row is postponed after an unexpected error before its claim. */
  static final Duration MIN_POSTPONEMENT = Duration.ofSeconds(1);

  /** Caps the exponent so the backoff cannot overflow. */
  private static final int MAX_DOUBLINGS = 30;

  private final int maxAttempts;
  private final Duration base;
  private final Jitter jitter;

  RetryPolicy(int maxAttempts, Duration base, Jitter jitter) {
    this.maxAttempts = maxAttempts;
    this.base = base;
    this.jitter = jitter;
  }

  /**
   * @param attempt the attempt that just failed, from 1
   * @param expiresAt the notification's expiry, or {@code null}
   */
  Next after(int attempt, Failed failure, Instant now, Instant expiresAt) {
    if (!failure.category().retryable()) {
      return new Next.Fail(failure.category().name());
    }
    if (exhausted(attempt)) {
      return new Next.Fail(RETRIES_EXHAUSTED);
    }
    Duration delay =
        failure
            .retryAfter()
            .map(RetryPolicy::clampRetryAfter)
            .orElseGet(() -> jitter.upTo(backoffCap(attempt)));
    Instant at = now.plus(delay);
    if (expiresAt != null && !at.isBefore(expiresAt)) {
      return new Next.Expire();
    }
    return new Next.Retry(at, delay);
  }

  /**
   * No attempt is left after {@code attempts} started. Checked before every claim too, so a row
   * that keeps crashing the worker mid-send (reclaimed, never answered) still ends.
   */
  boolean exhausted(int attempts) {
    return attempts >= maxAttempts;
  }

  /**
   * How far to push a row whose processing failed unexpectedly before its claim, so it stops
   * sorting first and blocking the batch.
   */
  Duration postponement(int attempts) {
    Duration backoff = backoffCap(attempts + 1);
    return backoff.compareTo(MIN_POSTPONEMENT) < 0 ? MIN_POSTPONEMENT : backoff;
  }

  static Duration clampRetryAfter(Duration retryAfter) {
    if (retryAfter.isNegative()) {
      return Duration.ZERO;
    }
    return retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
  }

  /** Upper bound of the jittered delay after {@code attempt}: {@code base * 2^(attempt - 1)}. */
  Duration backoffCap(int attempt) {
    return base.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), MAX_DOUBLINGS));
  }

  sealed interface Next {

    record Retry(Instant at, Duration delay) implements Next {}

    /** Final failure; {@code reason} is the category or {@link #RETRIES_EXHAUSTED}. */
    record Fail(String reason) implements Next {}

    /** The next attempt would be too late. */
    record Expire() implements Next {}
  }
}
