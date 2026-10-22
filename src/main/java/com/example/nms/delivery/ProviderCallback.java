package com.example.nms.delivery;

import com.example.nms.common.CallbackEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One validated provider callback.
 *
 * @param provider the provider authenticated by the webhook token, never one named in the body
 * @param providerMessageId stored only if the delivery has none yet (the callback beat our {@code
 *     SENT} write)
 * @param reasonCode the vendor's code, e.g. {@code MAILBOX_UNAVAILABLE}; may be null; audited, not
 *     stored on the delivery
 * @param occurredAt the vendor's time; validated but not stored: {@code finalized_at} is our
 *     receive time, so vendor clock skew cannot reorder history
 */
public record ProviderCallback(
    UUID deliveryId,
    String provider,
    CallbackEvent event,
    String providerMessageId,
    String reasonCode,
    Instant occurredAt) {

  public ProviderCallback {
    Objects.requireNonNull(deliveryId, "deliveryId");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(providerMessageId, "providerMessageId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
