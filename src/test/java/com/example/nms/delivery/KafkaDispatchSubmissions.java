package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.intake.IntakeService;
import com.example.nms.intake.SubmitCommand;
import com.example.nms.intake.SubmitResult;
import com.example.nms.intake.SubmittedNotification;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Submissions and waits shared by the Kafka fast path ITs (#47). */
final class KafkaDispatchSubmissions {

  private KafkaDispatchSubmissions() {}

  /** Submits one notification per user on {@code channel} only; returns the notification ids. */
  static List<UUID> submit(IntakeService intake, Channel channel, String... userIds) {
    SubmitResult result =
        intake.submit(
            new SubmitCommand(
                "demo-app",
                "key-" + UUID.randomUUID(),
                "evt-" + UUID.randomUUID(),
                "PAYMENT_DUE",
                Severity.INFO,
                Priority.NORMAL,
                Arrays.stream(userIds)
                    .map(id -> new SubmitCommand.Recipient(id, List.of(channel)))
                    .toList(),
                "subject",
                "body",
                null,
                Instant.now().truncatedTo(ChronoUnit.MICROS).plus(Duration.ofDays(1))));
    assertThat(result).isInstanceOf(SubmitResult.Created.class);
    return ((SubmitResult.Created) result)
        .notifications().stream().map(SubmittedNotification::notificationId).toList();
  }

  /** Waits until the notification's only delivery is {@code SENT}, and returns it. */
  static Delivery awaitSent(DeliveryRepository deliveries, UUID notificationId, Duration timeout) {
    return await()
        .atMost(timeout)
        .pollInterval(Duration.ofMillis(20))
        .until(
            () -> deliveries.findByNotificationIdOrderByChannel(notificationId).getFirst(),
            d -> d.getStatus() == DeliveryStatus.SENT);
  }

  /** {@code nms.kafka.published{outcome}}; 0 before the first publish. */
  static double published(MeterRegistry registry, String outcome) {
    Counter counter = registry.find("nms.kafka.published").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }
}
