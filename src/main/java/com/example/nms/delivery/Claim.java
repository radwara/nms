package com.example.nms.delivery;

import com.example.nms.common.Channel;
import com.example.nms.intake.Notification;
import java.util.UUID;

/**
 * A delivery this worker holds for one attempt. {@code version} is the row version the claim wrote:
 * the outcome write is conditional on it, so it changes nothing once another worker reclaimed the
 * row.
 */
record Claim(Delivery delivery, Notification notification, int attempt, long version) {

  /** The claim written by {@link DeliveryRepository#claim} for {@code delivery} as polled. */
  static Claim of(Delivery delivery, Notification notification) {
    return new Claim(
        delivery, notification, delivery.getAttemptCount() + 1, delivery.getVersion() + 1);
  }

  UUID id() {
    return delivery.getId();
  }

  Channel channel() {
    return delivery.getChannel();
  }
}
