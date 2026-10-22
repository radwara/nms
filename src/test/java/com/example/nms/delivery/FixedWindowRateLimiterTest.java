package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** #46: a fixed window per (source system, channel), aligned to multiples of the window. */
class FixedWindowRateLimiterTest {

  /** A window start: 2026-01-01T00:00:00Z is a whole minute. */
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  private static final Instant END = START.plus(Duration.ofMinutes(1));

  private final FixedWindowRateLimiter limiter =
      new FixedWindowRateLimiter(
          new RateLimitProperties(Duration.ofMinutes(1), Map.of(Channel.EMAIL, 2), 1));

  @Test
  void sendsUnderTheLimitAreAllowed() {
    assertThat(acquire(limiter, "billing", Channel.EMAIL, START)).isEmpty();
    assertThat(acquire(limiter, "billing", Channel.EMAIL, START.plusSeconds(30))).isEmpty();
  }

  @Test
  void aSendAtTheLimitIsDeferredToTheWindowEnd() {
    Instant mid = START.plusSeconds(20);
    acquire(limiter, "billing", Channel.EMAIL, mid);
    acquire(limiter, "billing", Channel.EMAIL, mid);

    assertThat(acquire(limiter, "billing", Channel.EMAIL, mid)).contains(END);
    assertThat(acquire(limiter, "billing", Channel.EMAIL, END.minusNanos(1))).contains(END);
  }

  @Test
  void aDeferredSendIsNotCounted() {
    acquire(limiter, "billing", Channel.EMAIL, START);
    acquire(limiter, "billing", Channel.EMAIL, START);
    acquire(limiter, "billing", Channel.EMAIL, START);

    assertThat(acquire(limiter, "billing", Channel.EMAIL, END)).isEmpty();
    assertThat(acquire(limiter, "billing", Channel.EMAIL, END)).isEmpty();
    assertThat(acquire(limiter, "billing", Channel.EMAIL, END)).isPresent();
  }

  @Test
  void theNextWindowStartsAFreshCount() {
    acquire(limiter, "billing", Channel.EMAIL, START);
    acquire(limiter, "billing", Channel.EMAIL, START);

    assertThat(acquire(limiter, "billing", Channel.EMAIL, END)).isEmpty();
  }

  @Test
  void sourceSystemsAndChannelsAreCountedSeparately() {
    acquire(limiter, "billing", Channel.EMAIL, START);
    acquire(limiter, "billing", Channel.EMAIL, START);

    assertThat(acquire(limiter, "billing", Channel.EMAIL, START)).isPresent();
    assertThat(acquire(limiter, "shipping", Channel.EMAIL, START)).isEmpty();
    assertThat(acquire(limiter, "billing", Channel.SMS, START)).isEmpty();
  }

  @Test
  void anUnlistedChannelGetsTheDefaultLimit() {
    assertThat(acquire(limiter, "billing", Channel.SMS, START)).isEmpty();

    assertThat(acquire(limiter, "billing", Channel.SMS, START)).contains(END);
  }

  @Test
  void windowsAreAlignedToMultiplesOfTheWindow() {
    FixedWindowRateLimiter tenSeconds =
        new FixedWindowRateLimiter(new RateLimitProperties(Duration.ofSeconds(10), Map.of(), 1));
    Instant at = START.plusSeconds(23);
    acquire(tenSeconds, "billing", Channel.EMAIL, at);

    assertThat(acquire(tenSeconds, "billing", Channel.EMAIL, at)).contains(START.plusSeconds(30));
    assertThat(acquire(tenSeconds, "billing", Channel.EMAIL, START.plusSeconds(30))).isEmpty();
  }

  @Test
  void anOlderInstantIsCountedInTheCurrentWindow() {
    acquire(limiter, "billing", Channel.SMS, END);

    assertThat(acquire(limiter, "billing", Channel.SMS, END.minusSeconds(1)))
        .contains(END.plus(Duration.ofMinutes(1)));
  }

  @Test
  void concurrentCallersNeverExceedTheLimitInAWindow() throws Exception {
    int limit = 100;
    int threads = 8;
    int perThread = 50;
    FixedWindowRateLimiter shared =
        new FixedWindowRateLimiter(
            new RateLimitProperties(Duration.ofMinutes(1), Map.of(Channel.EMAIL, limit), 1));
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> allowed = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int t = 0; t < threads; t++) {
        allowed.add(
            pool.submit(
                () -> {
                  go.await();
                  int count = 0;
                  for (int i = 0; i < perThread; i++) {
                    if (acquire(shared, "billing", Channel.EMAIL, START.plusMillis(i)).isEmpty()) {
                      count++;
                    }
                  }
                  return count;
                }));
      }
      go.countDown();
      int total = 0;
      for (Future<Integer> result : allowed) {
        total += result.get();
      }
      assertThat(total).isEqualTo(limit);
    }
  }

  @Test
  void aReleasedSlotCanBeTakenAgainInTheSameWindow() {
    RateLimiter.Key key = new RateLimiter.Key("billing", Channel.EMAIL);
    limiter.acquire(key, START);
    RateLimiter.Decision second = limiter.acquire(key, START);
    assertThat(acquire(limiter, "billing", Channel.EMAIL, START)).isPresent();

    limiter.release(key, ((RateLimiter.Allowed) second).windowStart());

    assertThat(acquire(limiter, "billing", Channel.EMAIL, START)).isEmpty();
    assertThat(acquire(limiter, "billing", Channel.EMAIL, START)).isPresent();
  }

  @Test
  void aReleaseForAPastWindowChangesNothing() {
    RateLimiter.Key key = new RateLimiter.Key("billing", Channel.EMAIL);
    RateLimiter.Decision old = limiter.acquire(key, START);
    acquire(limiter, "billing", Channel.EMAIL, END);
    acquire(limiter, "billing", Channel.EMAIL, END);

    limiter.release(key, ((RateLimiter.Allowed) old).windowStart());

    assertThat(acquire(limiter, "billing", Channel.EMAIL, END)).isPresent();
  }

  @Test
  void aClockThatFallsBackMoreThanOneWindowStartsANewWindow() {
    Instant later = START.plus(Duration.ofMinutes(5));
    acquire(limiter, "billing", Channel.SMS, later);

    assertThat(acquire(limiter, "billing", Channel.SMS, START)).as("new window at START").isEmpty();
    assertThat(acquire(limiter, "billing", Channel.SMS, START)).contains(END);
  }

  @Test
  void aClockThatFallsBackAtMostOneWindowCountsInTheCurrentWindow() {
    acquire(limiter, "billing", Channel.SMS, END);

    assertThat(acquire(limiter, "billing", Channel.SMS, START))
        .as("exactly one window back")
        .contains(END.plus(Duration.ofMinutes(1)));
  }

  @Test
  void anInstantBeforeTheEpochIsAlignedDownwards() {
    Instant before = Instant.EPOCH.minusSeconds(1);
    acquire(limiter, "billing", Channel.SMS, before);

    assertThat(acquire(limiter, "billing", Channel.SMS, before)).contains(Instant.EPOCH);
  }

  /** Empty if allowed, else when the key may send again. */
  private static Optional<Instant> acquire(
      FixedWindowRateLimiter limiter, String sourceSystem, Channel channel, Instant now) {
    return switch (limiter.acquire(new RateLimiter.Key(sourceSystem, channel), now)) {
      case RateLimiter.Allowed allowed -> Optional.empty();
      case RateLimiter.Denied denied -> Optional.of(denied.until());
    };
  }
}
