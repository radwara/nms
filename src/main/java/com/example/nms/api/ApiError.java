package com.example.nms.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

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

  /**
   * Generic error for {@code status} ({@link ErrorCode#forStatus}). When the code's own status
   * differs (e.g. 409 with 4000), the message is the status reason phrase.
   */
  public static ApiError forStatus(HttpStatusCode status) {
    ErrorCode code = ErrorCode.forStatus(status);
    if (code.status().value() == status.value()) {
      return of(code);
    }
    HttpStatus known = HttpStatus.resolve(status.value());
    return new ApiError(
        code, null, known == null ? code.defaultMessage() : known.getReasonPhrase());
  }
}
