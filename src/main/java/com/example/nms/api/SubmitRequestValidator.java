package com.example.nms.api;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.intake.RequestHash;
import com.example.nms.intake.SubmitCommand;
import com.example.nms.security.SourceSystem;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Validates a submit request (architecture.md, Submit and Errors) and turns it into a {@link
 * SubmitCommand}. The body is bound as a tree, so every field error is collected and thrown once;
 * 400-class errors win over 422-class ones ({@link ApiErrors}). Messages never contain submitted
 * values. Unknown properties, including {@code sourceSystem}, are ignored.
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

  // Timestamps outside years 1..9999 are rejected: not every database can store them.
  private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
  private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

  private final Clock clock;

  SubmitRequestValidator(Clock clock) {
    this.clock = clock;
  }

  /** The command for a valid request; otherwise throws {@link ApiException} with every error. */
  SubmitCommand validate(SourceSystem source, String idempotencyKey, JsonNode body) {
    ApiErrors errors = new ApiErrors();
    String key = idempotencyKey(idempotencyKey, errors);
    if (body == null || !body.isObject()) {
      errors.add(ErrorCode.INVALID_VALUE, null, "Request body must be a JSON object");
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

    // 422-class: well-formed but unacceptable. A past scheduledAt is sent now (A-16).
    if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
      errors.add(ErrorCode.EXPIRES_AT_IN_PAST, "expiresAt");
    }
    if (scheduledAt != null && expiresAt != null && scheduledAt.isAfter(expiresAt)) {
      errors.add(ErrorCode.SCHEDULED_AFTER_EXPIRY, "scheduledAt");
    }
    errors.throwIfAny();

    return new SubmitCommand(
        source.name(),
        key,
        RequestHash.of(body),
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

  private static String requiredText(
      JsonNode parent, String property, String path, int maxLength, ApiErrors errors) {
    JsonNode node = parent.get(property);
    if (node == null || node.isNull()) {
      errors.add(ErrorCode.FIELD_MISSING, path);
      return null;
    }
    if (!node.isString()) {
      errors.add(ErrorCode.INVALID_VALUE, path, "Must be a string");
      return null;
    }
    String value = node.stringValue();
    if (value.isBlank()) {
      errors.add(ErrorCode.FIELD_MISSING, path, "Must not be blank");
      return null;
    }
    if (value.length() > maxLength) {
      errors.add(ErrorCode.INVALID_VALUE, path, tooLong(maxLength));
      return null;
    }
    return value;
  }

  private static <E extends Enum<E>> E requiredEnum(
      JsonNode parent, String property, Class<E> type, ApiErrors errors) {
    JsonNode node = parent.get(property);
    if (node == null || node.isNull()) {
      errors.add(ErrorCode.FIELD_MISSING, property);
      return null;
    }
    return enumValue(node, property, type, errors);
  }

  private static <E extends Enum<E>> E enumValue(
      JsonNode node, String path, Class<E> type, ApiErrors errors) {
    if (node.isString()) {
      for (E constant : type.getEnumConstants()) {
        if (constant.name().equals(node.stringValue())) {
          return constant;
        }
      }
    }
    String allowed =
        Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
    errors.add(ErrorCode.INVALID_VALUE, path, "Must be one of " + allowed);
    return null;
  }

  /** ISO-8601 date-time with an offset or zone, e.g. {@code 2026-10-21T10:00:00Z}. */
  private static Instant optionalInstant(JsonNode parent, String property, ApiErrors errors) {
    JsonNode node = parent.get(property);
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isString()) {
      try {
        Instant instant = DateTimeFormatter.ISO_DATE_TIME.parse(node.stringValue(), Instant::from);
        if (!instant.isBefore(EARLIEST) && !instant.isAfter(LATEST)) {
          return instant.truncatedTo(ChronoUnit.MICROS);
        }
      } catch (DateTimeException e) {
        // reported below; the exception message holds the submitted value, so it is dropped
      }
    }
    errors.add(
        ErrorCode.INVALID_VALUE,
        property,
        "Must be an ISO-8601 date-time with offset, e.g. 2026-10-21T10:00:00Z");
    return null;
  }

  private static String tooLong(int maxLength) {
    return "Must be at most " + maxLength + " characters";
  }
}
