package com.example.nms.observability;

/**
 * A copy of a throwable chain for logging with every message replaced by the original class name.
 * Exception messages can carry submitted values (parse errors, constraint violations), so error
 * logs show types and stack frames only.
 */
public final class RedactedThrowable extends RuntimeException {

  private static final long serialVersionUID = 1L;
  private static final int MAX_CAUSES = 10;

  private RedactedThrowable(Throwable original, RedactedThrowable cause) {
    super(original.getClass().getName(), cause, false, true);
    setStackTrace(original.getStackTrace());
  }

  /** Redacted copy of {@code original} and up to ten causes. */
  public static RedactedThrowable of(Throwable original) {
    return copy(original, 0);
  }

  private static RedactedThrowable copy(Throwable original, int depth) {
    Throwable cause = original.getCause();
    RedactedThrowable redactedCause =
        cause == null || cause == original || depth >= MAX_CAUSES ? null : copy(cause, depth + 1);
    return new RedactedThrowable(original, redactedCause);
  }
}
