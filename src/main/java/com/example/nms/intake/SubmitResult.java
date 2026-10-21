package com.example.nms.intake;

import java.util.List;

/** Outcome of a submission; the web layer maps each case to a response. */
public sealed interface SubmitResult {

  /** New submission; notifications in request order. */
  record Created(List<SubmittedNotification> notifications) implements SubmitResult {

    public Created {
      notifications = List.copyOf(notifications);
    }
  }

  /** Same key and payload as an accepted submission; its notifications in request order. */
  record Replayed(List<SubmittedNotification> notifications) implements SubmitResult {

    public Replayed {
      notifications = List.copyOf(notifications);
    }
  }

  /** The key belongs to an accepted submission with a different payload. Nothing written. */
  record KeyConflict() implements SubmitResult {}

  /**
   * Another request with this key is still inside its transaction and outlasted the lock wait and
   * the replay retries. Nothing written; the caller should retry shortly.
   */
  record InProgress() implements SubmitResult {}

  /**
   * A new submission that is well-formed but cannot be accepted: time rules broken and recipients
   * not in the directory (request order), reported together. Nothing written.
   */
  record Unprocessable(List<TimeViolation> timeViolations, List<String> unknownRecipientIds)
      implements SubmitResult {

    public Unprocessable {
      timeViolations = List.copyOf(timeViolations);
      unknownRecipientIds = List.copyOf(unknownRecipientIds);
    }
  }
}
