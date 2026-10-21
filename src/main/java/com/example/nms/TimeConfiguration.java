package com.example.nms;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The single UTC clock behind every persisted timestamp, so tests can replace it. */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
