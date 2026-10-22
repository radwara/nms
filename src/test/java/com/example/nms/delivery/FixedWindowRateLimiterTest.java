package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
    assertThat(limiter.acquire("billing", Channel.EMAIL, START)).isEmpty();
    assertThat(limiter.acquire("billing", Channel.EMAIL, START.plusSeconds(30))).isEmpty();
  }

  @Test
  void aSendAtTheLimitIsDeferredToTheWindowEnd() {
    Instant mid = START.plusSeconds(20);
    limiter.acquire("billing", Channel.EMAIL, mid);
    limiter.acquire("billing", Channel.EMAIL, mid);

    assertThat(limiter.acquire("billing", Channel.EMAIL, mid)).contains(END);
    assertThat(limiter.acquire("billing", Channel.EMAIL, END.minusNanos(1))).contains(END);
  }

  @Test
  void aDeferredSendIsNotCounted() {
    limiter.acquire("billing", Channel.EMAIL, START);
    limiter.acquire("billing", Channel.EMAIL, START);
    limiter.acquire("billing", Channel.EMAIL, START);

    assertThat(limiter.acquire("billing", Channel.EMAIL, END)).isEmpty();
    assertThat(limiter.acquire("billing", Channel.EMAIL, END)).isEmpty();
    assertThat(limiter.acquire("billing", Channel.EMAIL, END)).isPresent();
  }

  @Test
  void theNextWindowStartsAFreshCount() {
    limiter.acquire("billing", Channel.EMAIL, START);
    limiter.acquire("billing", Channel.EMAIL, START);

    assertThat(limiter.acquire("billing", Channel.EMAIL, END)).isEmpty();
  }

  @Test
  void sourceSystemsAndChannelsAreCountedSeparately() {
    limiter.acquire("billing", Channel.EMAIL, START);
    limiter.acquire("billing", Channel.EMAIL, START);

    assertThat(limiter.acquire("billing", Channel.EMAIL, START)).isPresent();
    assertThat(limiter.acquire("shipping", Channel.EMAIL, START)).isEmpty();
    assertThat(limiter.acquire("billing", Channel.SMS, START)).isEmpty();
  }

  @Test
  void anUnlistedChannelGetsTheDefaultLimit() {
    assertThat(limiter.acquire("billing", Channel.SMS, START)).isEmpty();

    assertThat(limiter.acquire("billing", Channel.SMS, START)).contains(END);
  }

  @Test
  void windowsAreAlignedToMultiplesOfTheWindow() {
    FixedWindowRateLimiter tenSeconds =
        new FixedWindowRateLimiter(new RateLimitProperties(Duration.ofSeconds(10), Map.of(), 1));
    Instant at = START.plusSeconds(23);
    tenSeconds.acquire("billing", Channel.EMAIL, at);

    assertThat(tenSeconds.acquire("billing", Channel.EMAIL, at)).contains(START.plusSeconds(30));
    assertThat(tenSeconds.acquire("billing", Channel.EMAIL, START.plusSeconds(30))).isEmpty();
  }

  @Test
  void anInstantBeforeTheEpochIsAlignedDownwards() {
    Instant before = Instant.EPOCH.minusSeconds(1);
    limiter.acquire("billing", Channel.SMS, before);

    assertThat(limiter.acquire("billing", Channel.SMS, before)).contains(Instant.EPOCH);
  }
}
