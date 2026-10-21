package com.example.nms.provider;

import com.example.nms.provider.SendResult.Failed;
import java.util.Optional;

/**
 * Send outcomes of the simulated providers, selected by the recipient address (architecture.md
 * "Simulated outcomes"): an email send token or an SMS send code. Provider codes are HTTP-style.
 */
enum SimulatedSend {
  OK("ok", "00", null),
  TRANSIENT("transient", "01", new Failed(ErrorCategory.TRANSIENT, "503")),
  TIMEOUT("timeout", "02", new Failed(ErrorCategory.TIMEOUT, "504")),
  RATE_LIMITED(
      "ratelimit",
      "03",
      new Failed(ErrorCategory.RATE_LIMITED, "429", Optional.of(SimulatedProvider.RETRY_AFTER))),
  REJECT("reject", "04", new Failed(ErrorCategory.PERMANENT_REJECTION, "422")),
  INVALID("invalid", "05", new Failed(ErrorCategory.INVALID_RECIPIENT, "400")),
  AUTH_ERROR("autherror", "06", new Failed(ErrorCategory.AUTH_ERROR, "401")),
  /** Fails {@link SimulatedProvider#FLAKY_FAILURES} times per delivery, then accepts. */
  FLAKY2("flaky2", "12", new Failed(ErrorCategory.TRANSIENT, "503"));

  /** An address outside the convention is unknown to the simulated vendor. */
  static final Failed UNKNOWN_ADDRESS = new Failed(ErrorCategory.INVALID_RECIPIENT, "400");

  private final String emailToken;
  private final String smsCode;
  private final Failed failure;

  SimulatedSend(String emailToken, String smsCode, Failed failure) {
    this.emailToken = emailToken;
    this.smsCode = smsCode;
    this.failure = failure;
  }

  /** The failure this outcome returns; {@code null} for {@link #OK}. */
  Failed failure() {
    return failure;
  }

  static Optional<SimulatedSend> ofEmailToken(String token) {
    for (SimulatedSend send : values()) {
      if (send.emailToken.equals(token)) {
        return Optional.of(send);
      }
    }
    return Optional.empty();
  }

  static Optional<SimulatedSend> ofSmsCode(String code) {
    for (SimulatedSend send : values()) {
      if (send.smsCode.equals(code)) {
        return Optional.of(send);
      }
    }
    return Optional.empty();
  }
}
