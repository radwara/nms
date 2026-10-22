package com.example.nms.delivery;

import com.example.nms.audit.AuditRecorder;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.intake.Notification;
import com.example.nms.provider.SendResult.Failed;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outcome writes, each in its own transaction with its audit events. The update is conditional on
 * the claim's version; {@code false} means another writer changed the row first (a reclaim or a
 * callback), so nothing is written or audited.
 */
@Component
class DeliveryOutcomes {

  static final String EXPIRED_BEFORE_SEND = "before send";
  static final String EXPIRED_AFTER_LEASE = "lease expired after expiresAt";
  static final String EXPIRED_BEFORE_RETRY = "next attempt after expiresAt";

  private final DeliveryRepository deliveries;
  private final AuditRecorder audit;

  DeliveryOutcomes(DeliveryRepository deliveries, AuditRecorder audit) {
    this.deliveries = deliveries;
    this.audit = audit;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean sent(Claim claim, String providerMessageId, Instant now) {
    if (deliveries.markSent(claim.id(), claim.version(), providerMessageId, now) == 0) {
      return false;
    }
    Notification n = claim.notification();
    audit.sent(n.getSourceSystem(), n.getId(), claim.id(), claim.channel(), now);
    return true;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean retry(Claim claim, Failed failure, Instant at, Duration delay, Instant now) {
    if (deliveries.scheduleRetry(claim.id(), claim.version(), at, now) == 0) {
      return false;
    }
    attemptFailed(claim, failure, now);
    Notification n = claim.notification();
    audit.retryScheduled(n.getSourceSystem(), n.getId(), claim.id(), claim.channel(), delay, now);
    return true;
  }

  /** {@code reason} is the category, or {@code RETRIES_EXHAUSTED}. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean failed(Claim claim, Failed failure, String reason, Instant now) {
    if (finish(claim, DeliveryStatus.FAILED, reason, now) == 0) {
      return false;
    }
    attemptFailed(claim, failure, now);
    Notification n = claim.notification();
    audit.failed(n.getSourceSystem(), n.getId(), claim.id(), claim.channel(), reason, now);
    return true;
  }

  /** The attempt failed and the next one would fall after {@code expiresAt}. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean expiredAfterAttempt(Claim claim, Failed failure, Instant now) {
    if (finish(claim, DeliveryStatus.EXPIRED, null, now) == 0) {
      return false;
    }
    attemptFailed(claim, failure, now);
    Notification n = claim.notification();
    audit.expired(
        n.getSourceSystem(), n.getId(), claim.id(), claim.channel(), EXPIRED_BEFORE_RETRY, now);
    return true;
  }

  /**
   * A due delivery already past {@code expiresAt}, as polled (not claimed): no send. A reclaimed
   * {@code SENDING} row may have been sent by the worker that lost it, so its detail says so.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean expiredBeforeSend(Delivery due, Notification n, Instant now) {
    int updated =
        deliveries.finish(
            due.getId(), due.getStatus(), due.getVersion(), DeliveryStatus.EXPIRED, null, now);
    if (updated == 0) {
      return false;
    }
    String detail =
        due.getStatus() == DeliveryStatus.SENDING ? EXPIRED_AFTER_LEASE : EXPIRED_BEFORE_SEND;
    audit.expired(n.getSourceSystem(), n.getId(), due.getId(), due.getChannel(), detail, now);
    return true;
  }

  /**
   * A due delivery that cannot be sent, as polled (not claimed): {@code FAILED} with {@code
   * reason}, no send. Without its notification there is no source system to audit under, so only
   * the row is written.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean failedBeforeSend(Delivery due, Notification n, String reason, Instant now) {
    int updated =
        deliveries.finish(
            due.getId(), due.getStatus(), due.getVersion(), DeliveryStatus.FAILED, reason, now);
    if (updated == 0) {
      return false;
    }
    if (n != null) {
      audit.failed(n.getSourceSystem(), n.getId(), due.getId(), due.getChannel(), reason, now);
    }
    return true;
  }

  private int finish(Claim claim, DeliveryStatus status, String reason, Instant now) {
    return deliveries.finish(
        claim.id(), DeliveryStatus.SENDING, claim.version(), status, reason, now);
  }

  private void attemptFailed(Claim claim, Failed failure, Instant now) {
    Notification n = claim.notification();
    audit.attemptFailed(
        n.getSourceSystem(),
        n.getId(),
        claim.id(),
        claim.channel(),
        claim.attempt(),
        failure.category().name(),
        failure.providerCode(),
        now);
  }
}
