package com.example.nms.delivery;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Delivery metrics (architecture.md "Operations"). Tags are enum names only. */
@Component
class DeliveryMetrics {

  static final String ACCEPTED = "ACCEPTED";

  private final MeterRegistry registry;
  private final AtomicInteger due = new AtomicInteger();
  private final Timer pollTimer;

  DeliveryMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder("nms.deliveries.due", due, AtomicInteger::get)
        .description("Due deliveries seen by the last poll (at most batch-size per query)")
        .register(registry);
    pollTimer =
        Timer.builder("nms.worker.poll")
            .description("One worker poll: queries, claims, sends, and outcome writes")
            .register(registry);
  }

  /** One send call; {@code category} is the error category or {@link #ACCEPTED}. */
  void attempt(Channel channel, String category) {
    Counter.builder("nms.delivery.attempts")
        .description("Send calls by result")
        .tag("channel", channel.name())
        .tag("category", category)
        .register(registry)
        .increment();
  }

  /** A delivery reached {@code SENT}, {@code DELIVERED}, {@code FAILED}, or {@code EXPIRED}. */
  void outcome(Channel channel, DeliveryStatus status) {
    Counter.builder("nms.deliveries.outcome")
        .description("Deliveries reaching SENT, DELIVERED, FAILED, or EXPIRED")
        .tag("channel", channel.name())
        .tag("status", status.name())
        .register(registry)
        .increment();
  }

  /** Times one poll ({@code nms.worker.poll}). */
  <T> T timePoll(Supplier<T> poll) {
    return pollTimer.record(poll);
  }

  void due(int count) {
    due.set(count);
  }
}
