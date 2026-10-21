package com.example.nms.provider;

/** Classification of a failed send call (architecture.md "Failure classification"). */
public enum ErrorCategory {
  TRANSIENT(true),
  TIMEOUT(true),
  RATE_LIMITED(true),
  PERMANENT_REJECTION(false),
  INVALID_RECIPIENT(false),
  AUTH_ERROR(false);

  private final boolean retryable;

  ErrorCategory(boolean retryable) {
    this.retryable = retryable;
  }

  /** Whether the send may be retried with backoff; otherwise the delivery fails. */
  public boolean retryable() {
    return retryable;
  }
}
