package com.example.nms.intake;

import java.util.UUID;

/**
 * The id handed back for one recipient of a submission, with that recipient's outcome.
 *
 * @param duplicateOf the original's id when this notification was suppressed (ADR-0006); else null
 */
public record SubmittedNotification(String recipientId, UUID notificationId, UUID duplicateOf) {

  /** Outcome of one recipient of a new submission; a replay returns the original outcome. */
  public enum Outcome {
    ACCEPTED,
    /** A repeat of an earlier event and message: a row, but no deliveries. */
    SUPPRESSED
  }

  public SubmittedNotification(String recipientId, UUID notificationId) {
    this(recipientId, notificationId, null);
  }

  public Outcome outcome() {
    return duplicateOf == null ? Outcome.ACCEPTED : Outcome.SUPPRESSED;
  }
}
