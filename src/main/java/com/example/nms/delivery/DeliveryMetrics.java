package com.example.nms.delivery;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Delivery metrics (architecture.md "Operations"). Tags are enum names only. */
@Component
class DeliveryMetrics {

  static final String ACCEPTED = "ACCEPTED";

  private final MeterRegistry registry;
  private final AtomicInteger due = new AtomicInteger();

  DeliveryMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder("nms.deliveries.due", due, AtomicInteger::get)
        .description("Due deliveries seen by the last poll (at most batch-size per status)")
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

  /** A delivery reached {@code SENT}, {@code FAILED}, or {@code EXPIRED}. */
  void outcome(Channel channel, DeliveryStatus status) {
    Counter.builder("nms.deliveries.outcome")
        .description("Deliveries reaching SENT, FAILED, or EXPIRED")
        .tag("channel", channel.name())
        .tag("status", status.name())
        .register(registry)
        .increment();
  }

  void due(int count) {
    due.set(count);
  }
}
