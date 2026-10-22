package com.example.nms.delivery;

import com.example.nms.common.Channel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka fast path for EMAIL first dispatch (ADR-0008): after-commit publish, consumer, and the
 * poller's grace window. Off by default; with it off nothing here exists and the poller is
 * unchanged. Broker and client settings are Spring Boot's {@code spring.kafka.*}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nms.kafka.enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaDispatchProperties.class)
class KafkaDispatchConfiguration {

  /** Key and value are the delivery id; nothing else. */
  static final String TOPIC = "nms.delivery.email";

  @Bean
  FirstDispatchGrace firstDispatchGrace(KafkaDispatchProperties properties) {
    return new FirstDispatchGrace(Channel.EMAIL, properties.pollerGrace());
  }

  @Bean
  EmailDispatchPublisher emailDispatchPublisher(
      KafkaTemplate<String, String> kafka, DeliveryMetrics metrics) {
    return new EmailDispatchPublisher(kafka, metrics);
  }

  @Bean
  EmailDispatchListener emailDispatchListener(DeliveryWorker worker) {
    return new EmailDispatchListener(worker);
  }
}
