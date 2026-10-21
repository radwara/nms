package com.example.nms.provider;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Outcome of one send call. Acceptance is not delivery: the final outcome comes by callback. */
public sealed interface SendResult {

  /** The vendor accepted the message (ADR-0003: vendor {@code 200} = {@code SENT}). */
  record Accepted(String providerMessageId) implements SendResult {

    public Accepted {
      Objects.requireNonNull(providerMessageId, "providerMessageId");
    }
  }

  /**
   * The send call failed.
   *
   * @param providerCode the vendor's own code (e.g. HTTP status), a code only, never a message
   * @param retryAfter the vendor's {@code Retry-After}, if it sent one
   */
  record Failed(ErrorCategory category, String providerCode, Optional<Duration> retryAfter)
      implements SendResult {

    public Failed {
      Objects.requireNonNull(category, "category");
      Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Failed(ErrorCategory category, String providerCode) {
      this(category, providerCode, Optional.empty());
    }
  }
}
