package com.example.nms.intake;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import java.time.Instant;
import java.util.List;

/**
 * A validated submission. {@code sourceSystem} comes from the API key. Recipient order is the
 * request order. Its {@link RequestHash} decides whether a reused key is a replay.
 */
public record SubmitCommand(
    String sourceSystem,
    String idempotencyKey,
    String eventId,
    String type,
    Severity severity,
    Priority priority,
    List<Recipient> recipients,
    String subject,
    String body,
    Instant scheduledAt,
    Instant expiresAt) {

  public SubmitCommand {
    recipients = List.copyOf(recipients);
  }

  /** One recipient; {@code channels} is null when not requested (use preferences). */
  public record Recipient(String userId, List<Channel> channels) {

    public Recipient {
      channels = channels == null ? null : List.copyOf(channels);
    }
  }
}
