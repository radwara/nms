package com.example.nms.intake;

import java.util.UUID;

/** The id handed back for one recipient of a submission, with that recipient's outcome. */
public record SubmittedNotification(String recipientId, UUID notificationId) {

  /** Outcome of one recipient of a new submission; a replay returns the original outcome. */
  public enum Outcome {
    ACCEPTED
  }

  public Outcome outcome() {
    return Outcome.ACCEPTED;
  }
}
