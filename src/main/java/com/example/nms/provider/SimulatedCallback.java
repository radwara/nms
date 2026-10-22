package com.example.nms.provider;

import com.example.nms.common.CallbackEvent;
import java.util.Optional;

/**
 * Callback outcomes of the simulated providers, selected by the recipient address (architecture.md
 * "Simulated outcomes"): the email callback token or the SMS callback code. A token outside the
 * convention means no callback, like {@link #SILENT}.
 */
enum SimulatedCallback {
  DELIVERED("delivered", "00", CallbackEvent.DELIVERED, null),
  /** Email only: SMS has no bounce. */
  BOUNCED("bounce", null, CallbackEvent.BOUNCED, "MAILBOX_UNAVAILABLE"),
  FAILED("failed", "02", CallbackEvent.FAILED, "UNDELIVERABLE"),
  /** The vendor never calls back; the delivery stays {@code SENT}. */
  SILENT("silent", "09", null, null);

  private final String emailToken;
  private final String smsCode;
  private final CallbackEvent event;
  private final String reasonCode;

  SimulatedCallback(String emailToken, String smsCode, CallbackEvent event, String reasonCode) {
    this.emailToken = emailToken;
    this.smsCode = smsCode;
    this.event = event;
    this.reasonCode = reasonCode;
  }

  /** The event posted; empty for {@link #SILENT}. */
  Optional<CallbackEvent> event() {
    return Optional.ofNullable(event);
  }

  /** The vendor reason code sent with the event, or null. */
  String reasonCode() {
    return reasonCode;
  }

  static Optional<SimulatedCallback> ofEmailToken(String token) {
    for (SimulatedCallback callback : values()) {
      if (callback.emailToken.equals(token)) {
        return Optional.of(callback);
      }
    }
    return Optional.empty();
  }

  static Optional<SimulatedCallback> ofSmsCode(String code) {
    for (SimulatedCallback callback : values()) {
      if (code.equals(callback.smsCode)) {
        return Optional.of(callback);
      }
    }
    return Optional.empty();
  }
}
