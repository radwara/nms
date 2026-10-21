package com.example.nms.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to the standard error body. Messages come from {@link ErrorCode}, never from the
 * exception, so submitted values are not echoed.
 */
@RestControllerAdvice
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final ErrorResponseWriter writer;

  ApiExceptionHandler(ErrorResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(ApiException.class)
  void apiException(ApiException ex, HttpServletResponse response) throws IOException {
    writer.write(response, ex.errors());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  void notReadable(HttpServletResponse response) throws IOException {
    writer.write(response, ApiError.of(ErrorCode.MALFORMED_JSON));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  void unsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletResponse response)
      throws IOException {
    ex.getHeaders().forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
    writer.write(response, ApiError.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type"));
  }

  /**
   * Anything unexpected becomes 5000 with no detail; the exception is logged with the trace id.
   * Framework exceptions that already carry an HTTP status (404, 405, ...) are rethrown so Spring
   * handles them as before.
   */
  @ExceptionHandler(Exception.class)
  void unexpected(Exception ex, HttpServletResponse response) throws Exception {
    if (ex instanceof ErrorResponse) {
      throw ex;
    }
    log.error("Unhandled exception", ex);
    writer.write(response, ApiError.of(ErrorCode.INTERNAL_ERROR));
  }
}
