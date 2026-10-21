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

  /** Recipients not in the directory, in request order. Nothing written. */
  record UnknownRecipients(List<String> recipientIds) implements SubmitResult {

    public UnknownRecipients {
      recipientIds = List.copyOf(recipientIds);
    }
  }
}
