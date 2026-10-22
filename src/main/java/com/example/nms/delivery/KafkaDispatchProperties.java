package com.example.nms.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka fast path settings (ADR-0008), bound only with {@code nms.kafka.enabled=true}.
 *
 * @param pollerGrace how long a new EMAIL first dispatch is left to Kafka before the poller takes
 *     it
 * @param partitions partitions of the topic when this service creates it (an existing topic with
 *     fewer is extended; Kafka cannot remove partitions)
 * @param concurrency consumer threads; more than the partitions leaves some idle
 */
@ConfigurationProperties("nms.kafka")
record KafkaDispatchProperties(
    @DefaultValue("30s") Duration pollerGrace,
    @DefaultValue("3") int partitions,
    @DefaultValue("3") int concurrency) {

  KafkaDispatchProperties {
    require(!pollerGrace.isNegative() && !pollerGrace.isZero(), "poller-grace must be > 0");
    require(partitions > 0, "partitions must be > 0");
    require(concurrency > 0, "concurrency must be > 0");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException("nms.kafka." + message);
    }
  }
}
