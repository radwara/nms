package com.example.nms.provider;

import java.util.Objects;
import java.util.UUID;

/**
 * One send call. {@code deliveryId} is the provider idempotency key: a resend after a timeout or a
 * lease reclaim carries the same id, so the provider can dedupe it.
 *
 * <p>{@link #toString()} shows the id only; address and content must never reach a log.
 */
public record SendRequest(UUID deliveryId, String address, String subject, String body) {

  public SendRequest {
    Objects.requireNonNull(deliveryId, "deliveryId");
    Objects.requireNonNull(address, "address");
  }

  @Override
  public String toString() {
    return "SendRequest[deliveryId=" + deliveryId + "]";
  }
}
