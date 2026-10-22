package com.example.nms.status;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Status of one notification (one recipient): its deliveries, the derived overall status, and how
 * many idempotent replays it has seen. No content or contact details.
 *
 * @param requestedChannels as requested, in request order; null means preferences were used
 * @param duplicateSubmissions number of {@code DUPLICATE_SUBMISSION} audit events (FR-4.3)
 */
public record NotificationStatus(
    UUID notificationId,
    String recipientId,
    String eventId,
    String type,
    Severity severity,
    Priority priority,
    List<Channel> requestedChannels,
    OverallStatus overallStatus,
    long duplicateSubmissions,
    Instant createdAt,
    Instant scheduledAt,
    Instant expiresAt,
    List<DeliveryState> deliveries) {

  /**
   * One routed channel.
   *
   * @param attempts send attempts started
   * @param reason failure reason code, e.g. {@code INVALID_RECIPIENT} or {@code BOUNCED}; null
   *     unless failed
   */
  public record DeliveryState(
      UUID deliveryId,
      Channel channel,
      DeliveryStatus status,
      int attempts,
      String reason,
      Instant sentAt,
      Instant finalizedAt) {}
}
