package com.example.nms.api;

import static com.example.nms.api.JsonFields.enumValue;
import static com.example.nms.api.JsonFields.optionalInstant;
import static com.example.nms.api.JsonFields.requiredEnum;
import static com.example.nms.api.JsonFields.requiredText;
import static com.example.nms.api.JsonFields.tooLong;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.intake.SubmitCommand;
import com.example.nms.security.SourceSystem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Validates a submit request (architecture.md, Submit and Errors) and turns it into a {@link
 * SubmitCommand}. The body is bound as a tree, so every field error is collected and thrown once;
 * checks here are 400-class only. Time rules (4221, 4222) and recipient existence (4223) are
 * checked by intake after the idempotency lookup, so a retry of an accepted request still replays.
 * Messages never contain submitted values. Unknown properties, including {@code sourceSystem}, are
 * ignored.
 */
@Component
class SubmitRequestValidator {

  static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  static final int MAX_IDEMPOTENCY_KEY = 128;
  static final int MAX_EVENT_ID = 128;
  static final int MAX_TYPE = 64;
  static final int MAX_USER_ID = 64;
  static final int MAX_SUBJECT = 200;
  static final int MAX_BODY = 5000;
  static final int MAX_RECIPIENTS = 100;

  /** The command for a valid request; otherwise throws {@link ApiException} with every error. */
  SubmitCommand validate(SourceSystem source, String idempotencyKey, JsonNode body) {
    ApiErrors errors = new ApiErrors();
    String key = idempotencyKey(idempotencyKey, errors);
    if (body == null || !body.isObject()) {
      errors.add(ErrorCode.MALFORMED_JSON, null, "Request body must be a JSON object");
      errors.throwIfAny();
    }

    String eventId = requiredText(body, "eventId", "eventId", MAX_EVENT_ID, errors);
    String type = requiredText(body, "type", "type", MAX_TYPE, errors);
    Severity severity = requiredEnum(body, "severity", Severity.class, errors);
    Priority priority = requiredEnum(body, "priority", Priority.class, errors);
    List<SubmitCommand.Recipient> recipients = recipients(body, errors);
    String subject = requiredText(body, "subject", "subject", MAX_SUBJECT, errors);
    String content = requiredText(body, "body", "body", MAX_BODY, errors);
    Instant scheduledAt = optionalInstant(body, "scheduledAt", errors);
    Instant expiresAt = optionalInstant(body, "expiresAt", errors);

    errors.throwIfAny();

    return new SubmitCommand(
        source.name(),
        key,
        eventId,
        type,
        severity,
        priority,
        recipients,
        subject,
        content,
        scheduledAt,
        expiresAt);
  }

  private static String idempotencyKey(String key, ApiErrors errors) {
    if (key == null || key.isBlank()) {
      errors.add(ErrorCode.HEADER_MISSING, IDEMPOTENCY_KEY);
      return null;
    }
    if (key.length() > MAX_IDEMPOTENCY_KEY) {
      errors.add(ErrorCode.INVALID_VALUE, IDEMPOTENCY_KEY, tooLong(MAX_IDEMPOTENCY_KEY));
      return null;
    }
    return key;
  }

  private static List<SubmitCommand.Recipient> recipients(JsonNode body, ApiErrors errors) {
    JsonNode node = body.get("recipients");
    if (node == null || node.isNull()) {
      errors.add(ErrorCode.FIELD_MISSING, "recipients");
      return List.of();
    }
    if (!node.isArray()) {
      errors.add(ErrorCode.INVALID_VALUE, "recipients", "Must be an array");
      return List.of();
    }
    if (node.isEmpty()) {
      errors.add(ErrorCode.FIELD_MISSING, "recipients", "Must contain at least one recipient");
      return List.of();
    }
    if (node.size() > MAX_RECIPIENTS) {
      errors.add(ErrorCode.TOO_MANY_RECIPIENTS, "recipients");
      return List.of(); // entries are not checked: the request cannot succeed anyway
    }
    List<SubmitCommand.Recipient> recipients = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < node.size(); i++) {
      String path = "recipients[" + i + "]";
      JsonNode recipient = node.get(i);
      if (!recipient.isObject()) {
        errors.add(ErrorCode.INVALID_VALUE, path, "Must be an object");
        continue;
      }
      String userId = requiredText(recipient, "userId", path + ".userId", MAX_USER_ID, errors);
      if (userId != null && !seen.add(userId)) {
        errors.add(ErrorCode.INVALID_VALUE, path + ".userId", "Duplicate userId");
      }
      List<Channel> channels = channels(recipient, path + ".channels", errors);
      recipients.add(new SubmitCommand.Recipient(userId, channels));
    }
    return recipients;
  }

  /** Optional; when present it must name each channel once. {@code null} = use preferences. */
  private static List<Channel> channels(JsonNode recipient, String path, ApiErrors errors) {
    JsonNode node = recipient.get("channels");
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isArray()) {
      errors.add(ErrorCode.INVALID_VALUE, path, "Must be an array");
      return null;
    }
    if (node.isEmpty()) {
      errors.add(ErrorCode.INVALID_VALUE, path, "Must not be empty; omit it to use preferences");
      return null;
    }
    List<Channel> channels = new ArrayList<>();
    for (int j = 0; j < node.size(); j++) {
      String elementPath = path + "[" + j + "]";
      Channel channel = enumValue(node.get(j), elementPath, Channel.class, errors);
      if (channel != null && channels.contains(channel)) {
        errors.add(ErrorCode.INVALID_VALUE, elementPath, "Duplicate channel");
      } else if (channel != null) {
        channels.add(channel);
      }
    }
    return channels;
  }
}
