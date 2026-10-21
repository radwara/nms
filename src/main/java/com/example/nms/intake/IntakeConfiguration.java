package com.example.nms.intake;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
class IntakeConfiguration {

  /** Placeholder until routing (#9) provides the real step: creates no deliveries. */
  @Bean
  RoutingStep routingStep() {
    return (notification, now) -> {};
  }
}
