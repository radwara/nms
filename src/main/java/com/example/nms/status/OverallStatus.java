package com.example.nms.status;

import com.example.nms.common.DeliveryStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Overall status of one notification, derived on read from its deliveries and never stored (A-21,
 * architecture.md "Overall status"). A separate vocabulary from delivery {@code status}.
 * "Delivered" means every routed channel was delivered (A-22).
 */
public enum OverallStatus {
  /** Routing found no usable channel: there are no deliveries. */
  NOT_ROUTED,
  /** Before {@code scheduledAt}; nothing has been attempted. */
  SCHEDULED,
  /** Some delivery is still {@code PENDING}, {@code SENDING}, or {@code RETRY_SCHEDULED}. */
  IN_PROGRESS,
  /** Nothing left to send; some delivery is {@code SENT} and waits for its callback. */
  AWAITING_CONFIRMATION,
  DELIVERED,
  /** Some delivered, the rest failed or expired. */
  PARTIALLY_DELIVERED,
  /** None delivered, all expired. */
  EXPIRED,
  /** None delivered, at least one failed. */
  FAILED;

  private static final Set<DeliveryStatus> ACTIVE =
      EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.SENDING, DeliveryStatus.RETRY_SCHEDULED);

  /** {@link #derive(boolean, Instant, Instant, Collection)} for a notification not suppressed. */
  public static OverallStatus derive(
      Instant scheduledAt, Instant now, Collection<DeliveryStatus> deliveries) {
    return derive(false, scheduledAt, now, deliveries);
  }

  /**
   * The first matching rule wins: no deliveries; before {@code scheduledAt} with nothing attempted;
   * anything still active; anything awaiting a callback; then the final mix.
   *
   * @param suppressed whether the notification itself was suppressed at intake
   * @param scheduledAt the notification's schedule time, may be null
   */
  public static OverallStatus derive(
      boolean suppressed, Instant scheduledAt, Instant now, Collection<DeliveryStatus> deliveries) {
    if (deliveries.isEmpty()) {
      return NOT_ROUTED;
    }
    if (scheduledAt != null
        && now.isBefore(scheduledAt)
        && deliveries.stream().allMatch(s -> s == DeliveryStatus.PENDING)) {
      return SCHEDULED;
    }
    if (deliveries.stream().anyMatch(ACTIVE::contains)) {
      return IN_PROGRESS;
    }
    if (deliveries.contains(DeliveryStatus.SENT)) {
      return AWAITING_CONFIRMATION;
    }
    long delivered = deliveries.stream().filter(s -> s == DeliveryStatus.DELIVERED).count();
    if (delivered == deliveries.size()) {
      return DELIVERED;
    }
    if (delivered > 0) {
      return PARTIALLY_DELIVERED;
    }
    return deliveries.stream().allMatch(s -> s == DeliveryStatus.EXPIRED) ? EXPIRED : FAILED;
  }
}
