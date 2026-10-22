package com.example.nms.status;

import com.example.nms.audit.AuditEventRepository;
import com.example.nms.audit.AuditEventType;
import com.example.nms.delivery.Delivery;
import com.example.nms.delivery.DeliveryRepository;
import com.example.nms.intake.NotificationRepository;
import com.example.nms.status.NotificationStatus.DeliveryState;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status and audit reads. Both are scoped to the caller's source system in the query itself: a
 * notification of another source system is not found, exactly like an unknown id.
 */
@Component
public class StatusQueries {

  private final NotificationRepository notifications;
  private final DeliveryRepository deliveries;
  private final AuditEventRepository events;
  private final Clock clock;

  StatusQueries(
      NotificationRepository notifications,
      DeliveryRepository deliveries,
      AuditEventRepository events,
      Clock clock) {
    this.notifications = notifications;
    this.deliveries = deliveries;
    this.events = events;
    this.clock = clock;
  }

  /** The notification's status; empty if unknown or owned by another source system. */
  @Transactional(readOnly = true)
  public Optional<NotificationStatus> status(UUID notificationId, String sourceSystem) {
    return notifications
        .findByIdAndSourceSystem(notificationId, sourceSystem)
        .map(
            n -> {
              List<Delivery> rows = deliveries.findByNotificationIdOrderByChannel(n.getId());
              OverallStatus overall =
                  OverallStatus.derive(
                      n.getScheduledAt(),
                      clock.instant(),
                      rows.stream().map(Delivery::getStatus).toList());
              return new NotificationStatus(
                  n.getId(),
                  n.getRecipientId(),
                  n.getEventId(),
                  n.getType(),
                  n.getSeverity(),
                  n.getPriority(),
                  n.getRequestedChannels(),
                  overall,
                  events.countByNotificationIdAndType(
                      n.getId(), AuditEventType.DUPLICATE_SUBMISSION),
                  n.getCreatedAt(),
                  n.getScheduledAt(),
                  n.getExpiresAt(),
                  rows.stream().map(StatusQueries::state).toList());
            });
  }

  /**
   * The notification's history ordered by {@code occurred_at, id}: events written in one
   * transaction share a timestamp and keep insert order. Empty if unknown or owned by another
   * source system.
   */
  @Transactional(readOnly = true)
  public Optional<List<AuditEntry>> history(UUID notificationId, String sourceSystem) {
    if (!notifications.existsByIdAndSourceSystem(notificationId, sourceSystem)) {
      return Optional.empty();
    }
    return Optional.of(
        events.findByNotificationIdOrderByOccurredAtAscIdAsc(notificationId).stream()
            .map(e -> new AuditEntry(e.getOccurredAt(), e.getType(), e.getChannel(), e.getDetail()))
            .toList());
  }

  private static DeliveryState state(Delivery d) {
    return new DeliveryState(
        d.getId(),
        d.getChannel(),
        d.getStatus(),
        d.getAttemptCount(),
        d.getFailureReason(),
        d.getSentAt(),
        d.getFinalizedAt());
  }
}
