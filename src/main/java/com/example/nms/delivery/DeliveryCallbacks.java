package com.example.nms.delivery;

import com.example.nms.audit.AuditRecorder;
import com.example.nms.common.CallbackEvent;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies provider callbacks (ADR-0003): the only way a callback changes a delivery. The write is
 * conditional on {@code id}, {@code provider}, and {@code status in (SENDING, SENT)}, not on the
 * version, so a callback that beats the worker's {@code SENT} write still applies; that write is
 * conditional on the claim's version and then changes nothing. Final states never change: a repeat
 * or late callback is audited {@code CALLBACK_IGNORED}. The write, its audit, and the outcome
 * metric (after commit) belong to one transaction.
 */
@Component
public class DeliveryCallbacks {

  private static final Logger log = LoggerFactory.getLogger(DeliveryCallbacks.class);

  /** Failure reasons stored for callback events. */
  static final String BOUNCED = "BOUNCED";

  static final String CALLBACK_FAILED = "CALLBACK_FAILED";

  /** Ignored-callback details; the event name is appended. */
  static final String LATE_AFTER_EXPIRY = "late after expiry";

  static final String PROVIDER_MISMATCH = "provider mismatch";

  /** What a callback did. Every result is answered 200 so vendors do not retry. */
  public enum Result {
    /** The delivery reached its final state. */
    APPLIED,
    /** The delivery exists but did not change; audited {@code CALLBACK_IGNORED}. */
    IGNORED,
    /** No delivery has this id; logged only. */
    UNKNOWN
  }

  private final DeliveryRepository deliveries;
  private final NotificationRepository notifications;
  private final AuditRecorder audit;
  private final DeliveryMetrics metrics;
  private final Clock clock;

  DeliveryCallbacks(
      DeliveryRepository deliveries,
      NotificationRepository notifications,
      AuditRecorder audit,
      DeliveryMetrics metrics,
      Clock clock) {
    this.deliveries = deliveries;
    this.notifications = notifications;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Transactional
  public Result apply(ProviderCallback callback) {
    Optional<Delivery> found = deliveries.findById(callback.deliveryId());
    if (found.isEmpty()) {
      log.info(
          "Callback {} from {} for unknown delivery {}",
          callback.event(),
          callback.provider(),
          callback.deliveryId());
      return Result.UNKNOWN;
    }
    Delivery delivery = found.get();
    Notification notification = notifications.findById(delivery.getNotificationId()).orElseThrow();
    Instant now = clock.instant();
    if (!delivery.getProvider().equals(callback.provider())) {
      return ignored(delivery, notification, callback, PROVIDER_MISMATCH, now);
    }
    DeliveryStatus status =
        callback.event() == CallbackEvent.DELIVERED
            ? DeliveryStatus.DELIVERED
            : DeliveryStatus.FAILED;
    String reason = reason(callback.event());
    int updated =
        deliveries.applyCallback(
            delivery.getId(),
            callback.provider(),
            status,
            reason,
            callback.providerMessageId(),
            now);
    if (updated == 0) {
      DeliveryStatus current =
          deliveries.findById(delivery.getId()).map(Delivery::getStatus).orElseThrow();
      return ignored(delivery, notification, callback, why(current), now);
    }
    if (status == DeliveryStatus.DELIVERED) {
      audit.delivered(
          notification.getSourceSystem(),
          notification.getId(),
          delivery.getId(),
          delivery.getChannel(),
          detail(null, callback.reasonCode()),
          now);
    } else {
      audit.failed(
          notification.getSourceSystem(),
          notification.getId(),
          delivery.getId(),
          delivery.getChannel(),
          detail(reason, callback.reasonCode()),
          now);
    }
    afterCommit(() -> metrics.outcome(delivery.getChannel(), status));
    log.info(
        "Delivery {} on {}: callback {} -> {}",
        delivery.getId(),
        delivery.getChannel(),
        callback.event(),
        status);
    return Result.APPLIED;
  }

  private Result ignored(
      Delivery delivery,
      Notification notification,
      ProviderCallback callback,
      String why,
      Instant now) {
    audit.callbackIgnored(
        notification.getSourceSystem(),
        notification.getId(),
        delivery.getId(),
        delivery.getChannel(),
        why + "; event " + callback.event(),
        now);
    log.info(
        "Delivery {} on {}: callback {} from {} ignored ({})",
        delivery.getId(),
        delivery.getChannel(),
        callback.event(),
        callback.provider(),
        why);
    return Result.IGNORED;
  }

  /** Why a callback for a delivery in {@code status} (not SENDING or SENT) changes nothing. */
  static String why(DeliveryStatus status) {
    return switch (status) {
      case EXPIRED -> LATE_AFTER_EXPIRY;
      case DELIVERED, FAILED -> "already " + status;
      case PENDING, RETRY_SCHEDULED, SENDING, SENT -> "while " + status;
    };
  }

  private static String reason(CallbackEvent event) {
    return switch (event) {
      case DELIVERED -> null;
      case BOUNCED -> BOUNCED;
      case FAILED -> CALLBACK_FAILED;
    };
  }

  /** {@code reason} and the vendor's code, e.g. {@code BOUNCED, code MAILBOX_UNAVAILABLE}. */
  private static String detail(String reason, String reasonCode) {
    String code = reasonCode == null ? null : "code " + reasonCode;
    if (reason == null) {
      return code;
    }
    return code == null ? reason : reason + ", " + code;
  }

  private static void afterCommit(Runnable action) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }
}
