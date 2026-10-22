package com.example.nms.intake;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Event-level deduplication of a new submission (#14, ADR-0006). Runs inside the submit
 * transaction, after the idempotency key is claimed. A recipient is a duplicate when a routed
 * original notification with the same {@code (source system, event id, type, recipient, content
 * hash)} was created within the window. A {@code NOT_ROUTED} original (nothing sent) does not
 * count. Two concurrent submissions can both pass (documented limitation).
 */
@Component
class EventDedup {

  private final NotificationRepository notifications;
  private final IntakeMetrics metrics;
  private final boolean enabled;
  private final Duration window;

  EventDedup(
      NotificationRepository notifications, IntakeMetrics metrics, DedupProperties properties) {
    this.notifications = notifications;
    this.metrics = metrics;
    this.enabled = properties.enabled();
    this.window = properties.window();
  }

  /**
   * The recipients whose notification repeats an earlier one, mapped to that original's id (the
   * earliest in the window). Every other recipient is an original. Empty when disabled. One query
   * per submission; recipients are matched here, not in SQL, so the index needs no recipient
   * column.
   */
  Map<String, UUID> duplicates(SubmitCommand command, String contentHash, Instant now) {
    if (!enabled) {
      return Map.of();
    }
    Set<String> recipientIds =
        command.recipients().stream()
            .map(SubmitCommand.Recipient::userId)
            .collect(Collectors.toSet());
    Map<String, UUID> duplicates = new HashMap<>();
    for (EventOriginal original :
        metrics.timeDedupLookup(
            () ->
                notifications.findEventOriginals(
                    command.sourceSystem(),
                    command.eventId(),
                    command.type(),
                    contentHash,
                    now.minus(window)))) {
      if (recipientIds.contains(original.recipientId())) {
        duplicates.putIfAbsent(original.recipientId(), original.id());
      }
    }
    return duplicates;
  }
}
