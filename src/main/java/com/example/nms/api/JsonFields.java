package com.example.nms.api;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Field checks for request bodies bound as a JSON tree (architecture.md, Errors: lenient binding).
 * Each check adds its 400-class errors to {@link ApiErrors} and returns {@code null} on error, so a
 * validator reports every field at once. Messages never contain submitted values.
 */
final class JsonFields {

  // Timestamps outside years 1..9999 are rejected: not every database can store them.
  private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
  private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]+");

  private JsonFields() {}

  static String requiredText(
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

  static <E extends Enum<E>> E requiredEnum(
      JsonNode parent, String property, Class<E> type, ApiErrors errors) {
    JsonNode node = parent.get(property);
    if (node == null || node.isNull()) {
      errors.add(ErrorCode.FIELD_MISSING, property);
      return null;
    }
    return enumValue(node, property, type, errors);
  }

  static <E extends Enum<E>> E enumValue(
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
  static Instant optionalInstant(JsonNode parent, String property, ApiErrors errors) {
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

  static Instant requiredInstant(JsonNode parent, String property, ApiErrors errors) {
    JsonNode node = parent.get(property);
    if (node == null || node.isNull()) {
      errors.add(ErrorCode.FIELD_MISSING, property);
      return null;
    }
    return optionalInstant(parent, property, errors);
  }

  /** A UUID in canonical form, e.g. {@code 6f1c2a3b-...}. */
  static UUID requiredUuid(JsonNode parent, String property, ApiErrors errors) {
    String text = requiredText(parent, property, property, Uuids.TEXT_LENGTH, errors);
    if (text == null) {
      return null;
    }
    return Uuids.parse(text)
        .orElseGet(
            () -> {
              errors.add(ErrorCode.INVALID_VALUE, property, "Must be a UUID");
              return null;
            });
  }

  /**
   * Optional code made of letters, digits, {@code _ . -} only, so it can be audited: no free text,
   * addresses, or content fit.
   */
  static String optionalCode(JsonNode parent, String property, int maxLength, ApiErrors errors) {
    JsonNode node = parent.get(property);
    if (node == null || node.isNull()) {
      return null;
    }
    String text = requiredText(parent, property, property, maxLength, errors);
    if (text != null && !CODE.matcher(text).matches()) {
      errors.add(ErrorCode.INVALID_VALUE, property, "Must contain only letters, digits, _ . -");
      return null;
    }
    return text;
  }

  static String tooLong(int maxLength) {
    return "Must be at most " + maxLength + " characters";
  }
}
