package com.example.nms.intake;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Event-level deduplication of a new submission (#14, ADR-0006). Runs inside the submit
 * transaction, after the idempotency key is claimed. A recipient is a duplicate when an original
 * notification with the same {@code (source system, event id, type, recipient, content hash)} was
 * created within the window. Two concurrent submissions can both pass (documented limitation).
 */
@Component
class EventDedup {

  private final NotificationRepository notifications;
  private final boolean enabled;
  private final Duration window;

  EventDedup(NotificationRepository notifications, DedupProperties properties) {
    this.notifications = notifications;
    this.enabled = properties.enabled();
    this.window = properties.window();
  }

  /**
   * The recipients whose notification repeats an earlier one, mapped to that original's id (the
   * earliest in the window). Every other recipient is an original. Empty when disabled.
   */
  Map<String, UUID> duplicates(SubmitCommand command, String contentHash, Instant now) {
    if (!enabled) {
      return Map.of();
    }
    List<String> recipientIds =
        command.recipients().stream().map(SubmitCommand.Recipient::userId).toList();
    Map<String, UUID> duplicates = new HashMap<>();
    for (SubmittedNotification original :
        notifications.findEventOriginals(
            command.sourceSystem(),
            command.eventId(),
            command.type(),
            contentHash,
            recipientIds,
            now.minus(window))) {
      duplicates.putIfAbsent(original.recipientId(), original.notificationId());
    }
    return duplicates;
  }
}
