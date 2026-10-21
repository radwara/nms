package com.example.nms.intake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Time rules for a new submission, checked against the current time. Not applied to a replay: a
 * retry of an accepted request is answered even after its {@code expiresAt} has passed. A past
 * {@code scheduledAt} is allowed and sent now (A-16).
 */
public enum TimeViolation {
  EXPIRES_AT_IN_PAST,
  SCHEDULED_AFTER_EXPIRY;

  static List<TimeViolation> of(SubmitCommand command, Instant now) {
    List<TimeViolation> violations = new ArrayList<>();
    Instant expiresAt = command.expiresAt();
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      violations.add(EXPIRES_AT_IN_PAST);
    }
    Instant scheduledAt = command.scheduledAt();
    if (scheduledAt != null && expiresAt != null && scheduledAt.isAfter(expiresAt)) {
      violations.add(SCHEDULED_AFTER_EXPIRY);
    }
    return List.copyOf(violations);
  }
}
