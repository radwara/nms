package com.example.nms.api;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;

/**
 * An expected client error carrying one or more {@link ApiError}s, rendered as the standard error
 * body. All errors must map to the same HTTP status.
 */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<ApiError> errors;

  public ApiException(ApiError first, ApiError... more) {
    this(prepend(first, more));
  }

  public ApiException(List<ApiError> errors) {
    super(describe(errors), null, false, false);
    this.errors = List.copyOf(errors);
  }

  public List<ApiError> errors() {
    return errors;
  }

  public HttpStatus status() {
    return errors.getFirst().code().status();
  }

  private static List<ApiError> prepend(ApiError first, ApiError... more) {
    return Stream.concat(Stream.of(first), Arrays.stream(more)).toList();
  }

  /** Validates the errors and summarizes them by code only, never by value. */
  private static String describe(List<ApiError> errors) {
    if (errors.isEmpty()) {
      throw new IllegalArgumentException("ApiException needs at least one error");
    }
    long statuses = errors.stream().map(e -> e.code().status()).distinct().count();
    if (statuses > 1) {
      throw new IllegalArgumentException("ApiException errors must share one HTTP status");
    }
    return errors.stream()
        .map(e -> String.valueOf(e.code().code()))
        .collect(Collectors.joining(", ", "API error ", ""));
  }
}
