package com.example.nms.delivery;

import com.example.nms.common.Channel;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka fast path for EMAIL first dispatch (ADR-0008): topic, after-commit publish, consumer, and
 * the poller's grace window. Off by default; with it off nothing here exists and the poller is
 * unchanged. Broker and client settings are Spring Boot's {@code spring.kafka.*}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nms.kafka.enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaDispatchProperties.class)
class KafkaDispatchConfiguration {

  /** Key and value are the delivery id; nothing else. */
  static final String TOPIC = "nms.delivery.email";

  /** Created at startup if missing (Boot's {@code KafkaAdmin}); replication: broker default. */
  @Bean
  NewTopic emailDispatchTopic(KafkaDispatchProperties properties) {
    return TopicBuilder.name(TOPIC).partitions(properties.partitions()).build();
  }

  @Bean
  FirstDispatchGrace firstDispatchGrace(KafkaDispatchProperties properties) {
    return new FirstDispatchGrace(Channel.EMAIL, properties.pollerGrace());
  }

  /** Closed with the context: its publish thread stops, queued publishes are dropped (poller). */
  @Bean
  EmailDispatchPublisher emailDispatchPublisher(
      KafkaTemplate<String, String> kafka, DeliveryMetrics metrics) {
    return new EmailDispatchPublisher(kafka, metrics, EmailDispatchPublisher.publishExecutor());
  }

  @Bean
  EmailDispatchListener emailDispatchListener(DeliveryWorker worker) {
    return new EmailDispatchListener(worker);
  }
}
