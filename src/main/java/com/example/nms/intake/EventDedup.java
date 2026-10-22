package com.example.nms.intake;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Event-level deduplication of a new submission (#14). Runs inside the submit transaction, after
 * the idempotency key is claimed.
 */
@Component
class EventDedup {

  /**
   * The recipients whose notification repeats an earlier one, mapped to that original's id. Every
   * other recipient is an original. Currently none: every recipient is an original.
   */
  Map<String, UUID> duplicates(SubmitCommand command, Instant now) {
    return Map.of();
  }
}
