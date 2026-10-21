package com.example.nms.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfiguration {

  @Bean
  Jitter jitter() {
    return Jitter.uniform();
  }

  @Bean
  RetryPolicy retryPolicy(WorkerProperties properties, Jitter jitter) {
    return new RetryPolicy(properties.maxAttempts(), properties.backoffBase(), jitter);
  }

  /**
   * The poller: one fixed-delay task on Spring Boot's scheduler (one thread by default), so polls
   * never overlap. Off with {@code nms.worker.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(name = "nms.worker.enabled", havingValue = "true", matchIfMissing = true)
  static class Poller implements SchedulingConfigurer {

    private final DeliveryWorker worker;
    private final WorkerProperties properties;

    Poller(DeliveryWorker worker, WorkerProperties properties) {
      this.worker = worker;
      this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
      registrar.addFixedDelayTask(worker::runOnce, properties.pollInterval());
    }
  }
}
