package com.example.nms.delivery;

import com.example.nms.common.Channel;
import com.example.nms.observability.RedactedThrowable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes each EMAIL delivery id of a committed submission to {@link
 * KafkaDispatchConfiguration#TOPIC} (ADR-0008). The request thread only hands the ids to one
 * publish thread with a bounded queue, so it never waits for Kafka while holding its connection.
 * Never throws: a failed or rejected publish is logged by id and counted, and the poller sends the
 * row after the grace window.
 */
class EmailDispatchPublisher implements AutoCloseable {

  /** Submissions waiting to be published; beyond this they are counted failed (poller). */
  static final int QUEUE_CAPACITY = 1_000;

  private static final Logger log = LoggerFactory.getLogger(EmailDispatchPublisher.class);

  private final KafkaOperations<String, String> kafka;
  private final DeliveryMetrics metrics;
  private final Executor executor;

  EmailDispatchPublisher(
      KafkaOperations<String, String> kafka, DeliveryMetrics metrics, Executor executor) {
    this.kafka = kafka;
    this.metrics = metrics;
    this.executor = executor;
  }

  /** One daemon thread, {@link #QUEUE_CAPACITY} queued submissions, rejects when full. */
  static ExecutorService publishExecutor() {
    return new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
        task -> {
          Thread thread = new Thread(task, "kafka-publish");
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Creates the producer and fetches the topic's metadata once at startup, on the publish thread,
   * so the first submission does not pay for it. A broker that is down only logs.
   */
  @EventListener(ApplicationReadyEvent.class)
  void warmUp() {
    try {
      executor.execute(
          () -> {
            try {
              kafka.partitionsFor(KafkaDispatchConfiguration.TOPIC);
            } catch (RuntimeException e) {
              log.warn("Kafka not reachable at startup", RedactedThrowable.of(e));
            }
          });
    } catch (RejectedExecutionException e) {
      log.warn("Kafka producer warm-up not scheduled");
    }
  }

  /** Runs on the request thread after commit; returns without waiting for Kafka. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void publish(DeliveriesQueued event) {
    List<UUID> ids =
        event.deliveries().stream()
            .filter(d -> d.channel() == Channel.EMAIL)
            .map(DeliveriesQueued.Queued::id)
            .toList();
    if (ids.isEmpty()) {
      return;
    }
    try {
      executor.execute(() -> sendAll(ids));
    } catch (RejectedExecutionException e) {
      metrics.published(DeliveryMetrics.PUBLISH_FAILED, ids.size());
      log.warn(
          "Publish queue full; {} EMAIL deliveries left to the poller (first {})",
          ids.size(),
          ids.getFirst());
    }
  }

  /**
   * On the publish thread. A send that fails at once (no broker metadata within {@code
   * max.block.ms}) stops the rest, which would each wait as long; they are counted failed.
   */
  private void sendAll(List<UUID> ids) {
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

  /** Stops the publish thread; queued publishes are dropped and left to the poller. */
  @Override
  public void close() {
    if (executor instanceof ExecutorService service) {
      service.shutdownNow();
    }
  }
}
