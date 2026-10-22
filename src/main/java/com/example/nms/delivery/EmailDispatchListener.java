package com.example.nms.delivery;

import com.example.nms.observability.RedactedThrowable;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Consumes EMAIL delivery ids (ADR-0008) and runs each through {@link DeliveryWorker#process}: the
 * poll's per-row path, so a duplicate message or a race with the poller cannot send twice. Any
 * error is logged and the message is done; the poller is the retry.
 */
class EmailDispatchListener {

  /** Listener container id; the consumer group is {@code spring.kafka.consumer.group-id}. */
  static final String ID = "nms-delivery-email";

  private static final Logger log = LoggerFactory.getLogger(EmailDispatchListener.class);

  private final DeliveryWorker worker;

  EmailDispatchListener(DeliveryWorker worker) {
    this.worker = worker;
  }

  @KafkaListener(id = ID, idIsGroup = false, topics = KafkaDispatchConfiguration.TOPIC)
  void onMessage(String value) {
    UUID id;
    try {
      id = UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      log.warn("Message on {} is not a delivery id; skipped", KafkaDispatchConfiguration.TOPIC);
      return;
    }
    try {
      worker.process(id);
    } catch (RuntimeException e) {
      log.error(
          "Delivery {} not processed from Kafka; the poller sends it after the grace",
          id,
          RedactedThrowable.of(e));
    }
  }
}
