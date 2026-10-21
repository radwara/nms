package com.example.nms.api;

import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.http.HttpStatus;

/**
 * Stable error codes shared by every endpoint (architecture.md, Errors). Serialized as the code.
 */
public enum ErrorCode {
  HEADER_MISSING(4001, HttpStatus.BAD_REQUEST, "Required header is missing"),
  MALFORMED_JSON(4002, HttpStatus.BAD_REQUEST, "Malformed JSON"),
  INVALID_VALUE(4003, HttpStatus.BAD_REQUEST, "Invalid value"),
  FIELD_MISSING(4004, HttpStatus.BAD_REQUEST, "Required field is missing"),
  TOO_MANY_RECIPIENTS(4005, HttpStatus.BAD_REQUEST, "Too many recipients (max 100)"),
  CREDENTIALS_MISSING(4011, HttpStatus.UNAUTHORIZED, "Credentials are missing"),
  CREDENTIALS_INVALID(4012, HttpStatus.UNAUTHORIZED, "Credentials are invalid"),
  RESOURCE_NOT_FOUND(4040, HttpStatus.NOT_FOUND, "Resource not found"),
  NOTIFICATION_NOT_FOUND(4041, HttpStatus.NOT_FOUND, "Notification not found"),
  METHOD_NOT_ALLOWED(4050, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
  NOT_ACCEPTABLE(4060, HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
  IDEMPOTENCY_KEY_REUSED(
      4091, HttpStatus.CONFLICT, "Idempotency key reused with a different payload"),
  UNSUPPORTED_MEDIA_TYPE(4151, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type"),
  EXPIRES_AT_IN_PAST(4221, HttpStatus.UNPROCESSABLE_CONTENT, "expiresAt is in the past"),
  SCHEDULED_AFTER_EXPIRY(4222, HttpStatus.UNPROCESSABLE_CONTENT, "scheduledAt is after expiresAt"),
  UNKNOWN_RECIPIENT(4223, HttpStatus.UNPROCESSABLE_CONTENT, "Unknown recipient"),
  INTERNAL_ERROR(5000, HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

  private final int code;
  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(int code, HttpStatus status, String defaultMessage) {
    this.code = code;
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  @JsonValue
  public int code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }
}
