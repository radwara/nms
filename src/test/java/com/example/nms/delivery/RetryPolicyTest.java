package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.delivery.RetryPolicy.Next;
import com.example.nms.provider.ErrorCategory;
import com.example.nms.provider.SendResult.Failed;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** #10, A-40 - A-44: retry decisions after a failed attempt. */
class RetryPolicyTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Duration BASE = Duration.ofSeconds(2);
  private static final Failed TRANSIENT = new Failed(ErrorCategory.TRANSIENT, "503");

  /** Records each jitter bound and returns the bound itself (the longest delay). */
  private final List<Duration> bounds = new ArrayList<>();

  private final RetryPolicy policy =
      new RetryPolicy(
          4,
          BASE,
          max -> {
            bounds.add(max);
            return max;
          });

  @Test
  void backoffBoundDoublesPerAttempt() {
    assertThat(policy.backoffCap(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.backoffCap(2)).isEqualTo(Duration.ofSeconds(4));
    assertThat(policy.backoffCap(3)).isEqualTo(Duration.ofSeconds(8));
  }

  @Test
  void backoffBoundDoesNotOverflow() {
    assertThat(policy.backoffCap(10_000)).isEqualTo(BASE.multipliedBy(1L << 30));
  }

  @Test
  void retryableFailureRetriesAfterAJitteredBackoff() {
    Next next = policy.after(2, TRANSIENT, NOW, null);

    assertThat(next).isEqualTo(new Next.Retry(NOW.plusSeconds(4), Duration.ofSeconds(4)));
    assertThat(bounds).containsExactly(Duration.ofSeconds(4));
  }

  @Test
  void fullJitterDelayIsBetweenZeroAndTheBound() {
    RetryPolicy random = new RetryPolicy(4, BASE, Jitter.uniform());
    for (int i = 0; i < 200; i++) {
      Next.Retry retry = (Next.Retry) random.after(3, TRANSIENT, NOW, null);
      assertThat(retry.delay()).isBetween(Duration.ZERO, Duration.ofSeconds(8));
      assertThat(retry.at()).isEqualTo(NOW.plus(retry.delay()));
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = ErrorCategory.class,
      names = {"TRANSIENT", "TIMEOUT", "RATE_LIMITED"})
  void retryableCategoriesRetry(ErrorCategory category) {
    assertThat(policy.after(1, new Failed(category, "x"), NOW, null))
        .isInstanceOf(Next.Retry.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = ErrorCategory.class,
      names = {"PERMANENT_REJECTION", "INVALID_RECIPIENT", "AUTH_ERROR"})
  void nonRetryableCategoriesFailWithTheCategory(ErrorCategory category) {
    assertThat(policy.after(1, new Failed(category, "x"), NOW, null))
        .isEqualTo(new Next.Fail(category.name()));
  }

  @Test
  void lastAttemptFailsWithRetriesExhausted() {
    assertThat(policy.after(4, TRANSIENT, NOW, null))
        .isEqualTo(new Next.Fail(RetryPolicy.RETRIES_EXHAUSTED));
  }

  @Test
  void rateLimitedWaitsForRetryAfterWithoutJitter() {
    Failed limited =
        new Failed(ErrorCategory.RATE_LIMITED, "429", Optional.of(Duration.ofSeconds(30)));

    assertThat(policy.after(1, limited, NOW, null))
        .isEqualTo(new Next.Retry(NOW.plusSeconds(30), Duration.ofSeconds(30)));
    assertThat(bounds).isEmpty();
  }

  @Test
  void rateLimitedWithoutRetryAfterBacksOff() {
    assertThat(policy.after(1, new Failed(ErrorCategory.RATE_LIMITED, "429"), NOW, null))
        .isEqualTo(new Next.Retry(NOW.plusSeconds(2), Duration.ofSeconds(2)));
  }

  @Test
  void rateLimitedCountsTowardTheAttemptBudget() {
    Failed limited =
        new Failed(ErrorCategory.RATE_LIMITED, "429", Optional.of(Duration.ofSeconds(1)));

    assertThat(policy.after(4, limited, NOW, null))
        .isEqualTo(new Next.Fail(RetryPolicy.RETRIES_EXHAUSTED));
  }

  @Test
  void retryAtOrAfterExpiryExpires() {
    assertThat(policy.after(1, TRANSIENT, NOW, NOW.plusSeconds(2))).isEqualTo(new Next.Expire());
    assertThat(policy.after(1, TRANSIENT, NOW, NOW.plusSeconds(1))).isEqualTo(new Next.Expire());
  }

  @Test
  void retryBeforeExpiryIsScheduled() {
    assertThat(policy.after(1, TRANSIENT, NOW, NOW.plusSeconds(2).plusNanos(1_000)))
        .isInstanceOf(Next.Retry.class);
  }

  @Test
  void exhaustedOnceMaxAttemptsHaveStarted() {
    assertThat(policy.exhausted(3)).isFalse();
    assertThat(policy.exhausted(4)).isTrue();
    assertThat(policy.exhausted(5)).isTrue();
  }

  @Test
  void retryAfterLongerThanAnHourIsClamped() {
    Failed limited = new Failed(ErrorCategory.RATE_LIMITED, "429", Optional.of(Duration.ofDays(3)));

    assertThat(policy.after(1, limited, NOW, null))
        .isEqualTo(new Next.Retry(NOW.plus(Duration.ofHours(1)), Duration.ofHours(1)));
  }

  @Test
  void negativeRetryAfterIsClampedToZero() {
    Failed limited =
        new Failed(ErrorCategory.RATE_LIMITED, "429", Optional.of(Duration.ofSeconds(-5)));

    assertThat(policy.after(1, limited, NOW, null)).isEqualTo(new Next.Retry(NOW, Duration.ZERO));
  }

  @Test
  void clampedRetryAfterStillExpiresAtExpiresAt() {
    Failed limited = new Failed(ErrorCategory.RATE_LIMITED, "429", Optional.of(Duration.ofDays(3)));

    assertThat(policy.after(1, limited, NOW, NOW.plus(Duration.ofMinutes(30))))
        .isEqualTo(new Next.Expire());
  }

  @Test
  void postponementIsTheNextBackoffBoundAndAtLeastASecond() {
    assertThat(policy.postponement(0)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.postponement(2)).isEqualTo(Duration.ofSeconds(8));
    assertThat(new RetryPolicy(4, Duration.ZERO, max -> max).postponement(0))
        .isEqualTo(Duration.ofSeconds(1));
  }
}
