package com.example.nms.support;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.delivery.Delivery;
import com.example.nms.delivery.RoutingReason;
import com.example.nms.intake.IdempotencyRecord;
import com.example.nms.intake.Notification;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Valid entities for tests. Synthetic data only; the recipient is seeded by V2. */
public final class TestEntities {

  /** Fixed, microsecond-precision instant so values survive a database round trip unchanged. */
  public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  public static final String RECIPIENT = "user-ok";

  private static final String HASH = "a".repeat(64);

  private TestEntities() {}

  public static Notification notification() {
    return notification(null);
  }

  public static Notification notification(List<Channel> requestedChannels) {
    return new Notification(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "billing",
        "evt-1",
        "PAYMENT_DUE",
        Severity.INFO,
        Priority.HIGH,
        RECIPIENT,
        requestedChannels,
        "subject",
        "body",
        null,
        null,
        NOW);
  }

  public static Delivery delivery(UUID notificationId, Channel channel) {
    return new Delivery(
        UUID.randomUUID(),
        notificationId,
        channel,
        "sim-" + channel.name().toLowerCase(Locale.ROOT),
        Priority.HIGH,
        RoutingReason.REQUESTED,
        NOW,
        NOW);
  }

  public static IdempotencyRecord idempotencyRecord(String sourceSystem, String key) {
    return new IdempotencyRecord(
        sourceSystem, key, HASH, UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(7)));
  }
}
