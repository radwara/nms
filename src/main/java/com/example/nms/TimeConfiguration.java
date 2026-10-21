package com.example.nms;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single UTC clock behind every persisted timestamp, so tests can replace it. It ticks in
 * microseconds (database precision), so an instant read back equals the instant written.
 */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

  static final Duration TICK = Duration.ofNanos(1_000);

  @Bean
  Clock clock() {
    return Clock.tick(Clock.systemUTC(), TICK);
  }
}
