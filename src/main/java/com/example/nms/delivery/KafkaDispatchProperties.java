package com.example.nms.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka fast path settings (ADR-0008), bound only with {@code nms.kafka.enabled=true}.
 *
 * @param pollerGrace how long an EMAIL first dispatch is left to Kafka before the poller takes it
 */
@ConfigurationProperties("nms.kafka")
record KafkaDispatchProperties(@DefaultValue("30s") Duration pollerGrace) {

  KafkaDispatchProperties {
    if (pollerGrace.isNegative() || pollerGrace.isZero()) {
      throw new IllegalArgumentException("nms.kafka.poller-grace must be > 0");
    }
  }
}
