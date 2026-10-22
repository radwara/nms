package com.example.nms.delivery;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WorkerProperties.class, RateLimitProperties.class})
class WorkerConfiguration {

  @Bean
  Jitter jitter() {
    return Jitter.uniform();
  }

  @Bean
  RateLimiter rateLimiter(RateLimitProperties properties) {
    return new FixedWindowRateLimiter(properties);
  }

  @Bean
  RetryPolicy retryPolicy(WorkerProperties properties, Jitter jitter) {
    return new RetryPolicy(properties.maxAttempts(), properties.backoffBase(), jitter);
  }

  /**
   * The poller: one fixed-delay task on its own one-thread scheduler, so polls never overlap. A
   * full batch is followed by another poll at once, for up to one interval (backlog drain). On
   * shutdown the worker stops after the delivery in progress, and the scheduler waits longer than
   * one send for it. Off with {@code nms.worker.enabled=false}.
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

    /** Extra wait on shutdown beyond one send, for the outcome write. */
    static final Duration SHUTDOWN_MARGIN = Duration.ofSeconds(5);

    @Bean
    ThreadPoolTaskScheduler deliveryPollerScheduler() {
      ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
      scheduler.setPoolSize(1);
      scheduler.setThreadNamePrefix("delivery-poller-");
      scheduler.setWaitForTasksToCompleteOnShutdown(true);
      scheduler.setAwaitTerminationMillis(
          properties.sendTimeout().plus(SHUTDOWN_MARGIN).toMillis());
      return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
      Duration interval = properties.pollInterval();
      registrar.addFixedDelayTask(() -> worker.drain(interval), interval);
    }
  }
}
