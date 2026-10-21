package com.example.nms.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
    copyHeaders(ex, response);
    writer.write(response, ApiError.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type"));
  }

  /** Unknown route. The path is not echoed. */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  void notFound(HttpServletResponse response) throws IOException {
    writer.write(response, ApiError.of(ErrorCode.RESOURCE_NOT_FOUND));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletResponse response)
      throws IOException {
    copyHeaders(ex, response); // Allow
    writer.write(response, ApiError.of(ErrorCode.METHOD_NOT_ALLOWED));
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  void notAcceptable(HttpServletResponse response) throws IOException {
    writer.write(response, ApiError.of(ErrorCode.NOT_ACCEPTABLE, "Accept"));
  }

  /**
   * Anything unexpected becomes 5000 with no detail; the exception is logged with the trace id.
   * Other framework exceptions that carry an HTTP status (e.g. missing header, until the API maps
   * them) are rethrown so Spring handles them as before.
   */
  @ExceptionHandler(Exception.class)
  void unexpected(Exception ex, HttpServletResponse response) throws Exception {
    if (ex instanceof ErrorResponse) {
      throw ex;
    }
    log.error("Unhandled exception", ex);
    writer.write(response, ApiError.of(ErrorCode.INTERNAL_ERROR));
  }

  private static void copyHeaders(ErrorResponse ex, HttpServletResponse response) {
    ex.getHeaders().forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
  }
}
