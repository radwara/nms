package com.example.nms.intake;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SHA-256 (lowercase hex) of a submission's canonical form, built from the validated fields only:
 * unknown properties, whitespace, and key order cannot change it; an absent and a null optional
 * field are the same; timestamps are UTC instants. Recipient and channel order are kept: they are
 * part of the payload. The source system and key are the record's identity, not its payload.
 */
public final class RequestHash {

  private RequestHash() {}

  public static String of(SubmitCommand command) {
    byte[] canonical = JsonMapper.shared().writeValueAsBytes(canonical(command));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }

  /** Properties in key order. */
  static ObjectNode canonical(SubmitCommand command) {
    ObjectNode node = JsonMapper.shared().createObjectNode();
    node.put("body", command.body());
    node.put("eventId", command.eventId());
    node.put("expiresAt", iso(command.expiresAt()));
    node.put("priority", command.priority().name());
    ArrayNode recipients = node.putArray("recipients");
    for (SubmitCommand.Recipient recipient : command.recipients()) {
      ObjectNode entry = recipients.addObject();
      if (recipient.channels() == null) {
        entry.putNull("channels");
      } else {
        ArrayNode channels = entry.putArray("channels");
        recipient.channels().forEach(channel -> channels.add(channel.name()));
      }
      entry.put("userId", recipient.userId());
    }
    node.put("scheduledAt", iso(command.scheduledAt()));
    node.put("severity", command.severity().name());
    node.put("subject", command.subject());
    node.put("type", command.type());
    return node;
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
