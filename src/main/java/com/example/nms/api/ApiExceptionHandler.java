package com.example.nms.api;

import com.example.nms.observability.RedactedThrowable;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Maps every exception to the standard error body. Messages come from {@link ErrorCode}, never from
 * the exception, so submitted values are not echoed; {@code field} names the header, parameter, or
 * JSON path.
 */
@RestControllerAdvice
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final List<String> MISSING_VALUE_CONSTRAINTS =
      List.of("NotNull", "NotBlank", "NotEmpty");

  private final ErrorResponseWriter writer;

  ApiExceptionHandler(ErrorResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(ApiException.class)
  void apiException(ApiException ex, HttpServletResponse response) throws IOException {
    writer.write(response, ex.errors());
  }

  /**
   * Wrong JSON type or format for a field is 4003 with its path; unparseable JSON is 4002; a
   * document over the size limit is 4130.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  void notReadable(HttpMessageNotReadableException ex, HttpServletResponse response)
      throws IOException {
    writer.write(response, notReadableError(ex));
  }

  /**
   * A stream constraint (document over {@link JsonLimitsConfiguration#MAX_DOCUMENT_BYTES}, or
   * nesting or number size beyond Jackson's limits) is 413 / 4130.
   */
  static ApiError notReadableError(HttpMessageNotReadableException ex) {
    if (ex.getCause() instanceof StreamConstraintsException) {
      return ApiError.of(ErrorCode.PAYLOAD_TOO_LARGE);
    }
    return ex.getCause() instanceof MismatchedInputException mismatch
        ? ApiError.of(ErrorCode.INVALID_VALUE, jsonPath(mismatch))
        : ApiError.of(ErrorCode.MALFORMED_JSON);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  void missingHeader(MissingRequestHeaderException ex, HttpServletResponse response)
      throws IOException {
    writer.write(response, ApiError.of(ErrorCode.HEADER_MISSING, ex.getHeaderName()));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  void missingParameter(MissingServletRequestParameterException ex, HttpServletResponse response)
      throws IOException {
    writer.write(response, ApiError.of(ErrorCode.FIELD_MISSING, ex.getParameterName()));
  }

  /** A path variable, parameter, or header that cannot be converted. The value is not logged. */
  @ExceptionHandler(TypeMismatchException.class)
  void typeMismatch(TypeMismatchException ex, HttpServletResponse response) throws IOException {
    if (ex instanceof ConversionNotSupportedException) {
      unexpected(ex, response); // no converter at all: a server bug, not bad input
      return;
    }
    String field =
        ex instanceof MethodArgumentTypeMismatchException argument
            ? argument.getName()
            : ex.getPropertyName();
    writer.write(response, ApiError.of(ErrorCode.INVALID_VALUE, field));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  void notValid(MethodArgumentNotValidException ex, HttpServletResponse response)
      throws IOException {
    List<ApiError> errors = new ArrayList<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(e -> errors.add(ApiError.of(constraintCode(e), e.getField())));
    ex.getBindingResult()
        .getGlobalErrors()
        .forEach(e -> errors.add(ApiError.of(ErrorCode.INVALID_VALUE)));
    writer.write(response, errors);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  void methodNotValid(HandlerMethodValidationException ex, HttpServletResponse response)
      throws IOException {
    List<ApiError> errors = new ArrayList<>();
    for (ParameterValidationResult result : ex.getParameterValidationResults()) {
      if (result instanceof ParameterErrors parameterErrors) {
        parameterErrors
            .getFieldErrors()
            .forEach(e -> errors.add(ApiError.of(constraintCode(e), e.getField())));
      } else {
        String name = boundName(result.getMethodParameter());
        result.getResolvableErrors().forEach(e -> errors.add(ApiError.of(constraintCode(e), name)));
      }
    }
    ex.getCrossParameterValidationResults()
        .forEach(e -> errors.add(ApiError.of(ErrorCode.INVALID_VALUE)));
    if (errors.isEmpty()) {
      errors.add(ApiError.of(ErrorCode.INVALID_VALUE));
    }
    writer.write(response, errors);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  void unsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletResponse response)
      throws IOException {
    copyHeaders(ex, response); // Accept
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
   * Other framework exceptions keep their status with a generic code ({@link ErrorCode#forStatus}).
   * Anything else is 5000 with no detail, logged without its message. Client disconnects are not
   * errors.
   */
  @ExceptionHandler(Exception.class)
  void unexpected(Exception ex, HttpServletResponse response) throws IOException {
    if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
      log.debug("Client disconnected ({})", ex.getClass().getName());
      return;
    }
    HttpStatusCode status =
        ex instanceof ErrorResponse errorResponse
            ? errorResponse.getStatusCode()
            : ErrorCode.INTERNAL_ERROR.status();
    if (status.is5xxServerError()) {
      log.error("Request failed with status {}", status.value(), RedactedThrowable.of(ex));
    }
    if (response.isCommitted()) {
      return;
    }
    if (ex instanceof ErrorResponse errorResponse) {
      copyHeaders(errorResponse, response);
    }
    writer.write(response, status, List.of(ApiError.forStatus(status)));
  }

  private static ErrorCode constraintCode(MessageSourceResolvable error) {
    String[] codes = error.getCodes();
    if (codes != null) {
      for (String code : codes) {
        if (MISSING_VALUE_CONSTRAINTS.contains(code)) {
          return ErrorCode.FIELD_MISSING;
        }
      }
    }
    return ErrorCode.INVALID_VALUE;
  }

  /** The name the client used: header, parameter, or path variable name, else the Java name. */
  private static String boundName(MethodParameter parameter) {
    String name = null;
    RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
    RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
    PathVariable variable = parameter.getParameterAnnotation(PathVariable.class);
    if (header != null) {
      name = header.name().isEmpty() ? header.value() : header.name();
    } else if (param != null) {
      name = param.name().isEmpty() ? param.value() : param.name();
    } else if (variable != null) {
      name = variable.name().isEmpty() ? variable.value() : variable.name();
    }
    return name == null || name.isEmpty() ? parameter.getParameterName() : name;
  }

  /** JSON path such as {@code recipients[2].userId}; {@code null} for the root. */
  static String jsonPath(JacksonException ex) {
    StringBuilder path = new StringBuilder();
    for (JacksonException.Reference ref : ex.getPath()) {
      if (ref.getPropertyName() != null) {
        if (!path.isEmpty()) {
          path.append('.');
        }
        path.append(ref.getPropertyName());
      } else if (ref.getIndex() >= 0) {
        path.append('[').append(ref.getIndex()).append(']');
      }
    }
    return path.isEmpty() ? null : path.toString();
  }

  private static void copyHeaders(ErrorResponse ex, HttpServletResponse response) {
    ex.getHeaders().forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
  }
}
