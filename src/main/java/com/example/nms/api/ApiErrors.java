package com.example.nms.api;

import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Collects validation errors so a request reports all of them at once. Only 400 and 422 codes are
 * accepted. 400-class errors win: 422-class errors are thrown only when no 400-class error exists,
 * because semantic checks are meaningless on input that is malformed.
 */
public final class ApiErrors {

  private final List<ApiError> errors = new ArrayList<>();

  public ApiErrors add(ErrorCode code, String field) {
    return add(ApiError.of(code, field));
  }

  public ApiErrors add(ErrorCode code, String field, String message) {
    return add(new ApiError(code, field, message));
  }

  public ApiErrors add(ApiError error) {
    HttpStatus status = error.code().status();
    if (status != HttpStatus.BAD_REQUEST && status != HttpStatus.UNPROCESSABLE_CONTENT) {
      throw new IllegalArgumentException(
          "ApiErrors collects 400 and 422 errors only, not " + error.code().code());
    }
    errors.add(error);
    return this;
  }

  public boolean isEmpty() {
    return errors.isEmpty();
  }

  /** Throws the 400-class errors if any, else the 422-class errors if any. */
  public void throwIfAny() {
    if (errors.isEmpty()) {
      return;
    }
    List<ApiError> badRequest =
        errors.stream().filter(e -> e.code().status() == HttpStatus.BAD_REQUEST).toList();
    throw new ApiException(badRequest.isEmpty() ? errors : badRequest);
  }
}
