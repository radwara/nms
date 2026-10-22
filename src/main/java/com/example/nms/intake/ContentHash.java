package com.example.nms.intake;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * SHA-256 (lowercase hex) of a submission's message: {@code {"body", "severity", "subject"}} in
 * that key order, values exactly as validated (ADR-0006). Part of the event dedup key, so only an
 * identical message is suppressed. Not in it: event id, type, recipients, channels, priority, and
 * times. Unsalted and guessable for short text, so it is never returned, logged, or audited.
 */
final class ContentHash {

  private ContentHash() {}

  static String of(SubmitCommand command) {
    return RequestHash.sha256Hex(canonical(command));
  }

  /** Properties in key order; JSON quoting keeps field boundaries unambiguous. */
  static ObjectNode canonical(SubmitCommand command) {
    ObjectNode node = JsonMapper.shared().createObjectNode();
    node.put("body", command.body());
    node.put("severity", command.severity().name());
    node.put("subject", command.subject());
    return node;
  }
}
