package com.example.nms.delivery;

import com.example.nms.common.Channel;
import com.example.nms.observability.RedactedThrowable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes each EMAIL delivery id of a committed submission to {@link
 * KafkaDispatchConfiguration#TOPIC} (ADR-0008). Never throws: a failed publish is logged by id and
 * counted, and the poller sends the row after the grace window.
 */
class EmailDispatchPublisher {

  private static final Logger log = LoggerFactory.getLogger(EmailDispatchPublisher.class);

  private final KafkaOperations<String, String> kafka;
  private final DeliveryMetrics metrics;

  EmailDispatchPublisher(KafkaOperations<String, String> kafka, DeliveryMetrics metrics) {
    this.kafka = kafka;
    this.metrics = metrics;
  }

  /**
   * Runs on the request thread after commit. A send that fails at once (no broker metadata within
   * {@code max.block.ms}) stops the rest, which would each wait as long; they are counted failed.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void publish(DeliveriesQueued event) {
    List<UUID> ids =
        event.deliveries().stream()
            .filter(d -> d.channel() == Channel.EMAIL)
            .map(DeliveriesQueued.Queued::id)
            .toList();
    for (int i = 0; i < ids.size(); i++) {
      if (!send(ids.get(i))) {
        int rest = ids.size() - i - 1;
        if (rest > 0) {
          metrics.published(DeliveryMetrics.PUBLISH_FAILED, rest);
          log.warn(
              "{} more EMAIL deliveries not published; the poller sends them after the grace",
              rest);
        }
        return;
      }
    }
  }

  /** {@code false} if the send failed before returning. */
  private boolean send(UUID id) {
    String value = id.toString();
    CompletableFuture<SendResult<String, String>> sent;
    try {
      sent = kafka.send(KafkaDispatchConfiguration.TOPIC, value, value);
    } catch (RuntimeException e) {
      failed(id, e);
      return false;
    }
    sent.whenComplete(
        (result, e) -> {
          if (e == null) {
            metrics.published(DeliveryMetrics.PUBLISHED, 1);
          } else {
            failed(id, e);
          }
        });
    return !sent.isCompletedExceptionally();
  }

  private void failed(UUID id, Throwable cause) {
    metrics.published(DeliveryMetrics.PUBLISH_FAILED, 1);
    log.warn(
        "Delivery {} not published to Kafka; the poller sends it after the grace",
        id,
        RedactedThrowable.of(cause));
  }
}
