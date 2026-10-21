package com.example.nms.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * One entry of the error body. {@code field} names the offending header or JSON path, never its
 * value; it is omitted when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(ErrorCode code, String field, String message) {

  public ApiError {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }

  public static ApiError of(ErrorCode code) {
    return new ApiError(code, null, code.defaultMessage());
  }

  public static ApiError of(ErrorCode code, String field) {
    return new ApiError(code, field, code.defaultMessage());
  }
}
