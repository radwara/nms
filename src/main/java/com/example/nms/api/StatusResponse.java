package com.example.nms.api;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.status.NotificationStatus;
import com.example.nms.status.OverallStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code GET /notifications/{id}} (architecture.md, Status). Top-level timestamps are
 * always present (null when unset); per-delivery {@code reason}, {@code sentAt}, and {@code
 * finalizedAt} are omitted until they apply.
 */
record StatusResponse(
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
    List<Item> deliveries) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Item(
      UUID deliveryId,
      Channel channel,
      DeliveryStatus status,
      int attempts,
      String reason,
      Instant sentAt,
      Instant finalizedAt) {}

  static StatusResponse of(NotificationStatus s) {
    return new StatusResponse(
        s.notificationId(),
        s.recipientId(),
        s.eventId(),
        s.type(),
        s.severity(),
        s.priority(),
        s.requestedChannels(),
        s.overallStatus(),
        s.duplicateSubmissions(),
        s.createdAt(),
        s.scheduledAt(),
        s.expiresAt(),
        s.deliveries().stream()
            .map(
                d ->
                    new Item(
                        d.deliveryId(),
                        d.channel(),
                        d.status(),
                        d.attempts(),
                        d.reason(),
                        d.sentAt(),
                        d.finalizedAt()))
            .toList());
  }
}
