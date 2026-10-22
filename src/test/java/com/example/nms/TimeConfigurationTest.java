package com.example.nms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** #10: the injected clock is UTC and truncated to microseconds (database precision). */
class TimeConfigurationTest {

  private final Clock clock = new TimeConfiguration().clock();

  @RepeatedTest(20)
  void instantsHaveMicrosecondPrecision() {
    assertThat(clock.instant().getNano() % 1_000).isZero();
  }

  @Test
  void clockIsUtc() {
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }
}
