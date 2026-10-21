package com.example.nms.api;

import com.example.nms.audit.AuditRecorder;
import com.example.nms.observability.RedactedThrowable;
import com.example.nms.security.SourceSystem;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Audits every submit rejection after authentication (400, 409, 422) as {@code REJECTED} with its
 * error codes, then writes the standard error body. Runs before {@link ApiExceptionHandler} for
 * {@link NotificationController} only. Unauthenticated requests never reach a controller, so they
 * are not audited.
 */
@RestControllerAdvice(assignableTypes = NotificationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class SubmitRejectionAudit {

  private static final Logger log = LoggerFactory.getLogger(SubmitRejectionAudit.class);

  private final AuditRecorder audit;
  private final ErrorResponseWriter writer;

  SubmitRejectionAudit(AuditRecorder audit, ErrorResponseWriter writer) {
    this.audit = audit;
    this.writer = writer;
  }

  @ExceptionHandler(ApiException.class)
  void rejected(ApiException ex, SourceSystem source, HttpServletResponse response)
      throws IOException {
    record(source, ex.errors());
    writer.write(response, ex.errors());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  void unreadable(
      HttpMessageNotReadableException ex, SourceSystem source, HttpServletResponse response)
      throws IOException {
    ApiError error = ApiExceptionHandler.notReadableError(ex);
    record(source, List.of(error));
    writer.write(response, error);
  }

  /** An audit failure is logged, not returned: the client's error stays the client's error. */
  private void record(SourceSystem source, List<ApiError> errors) {
    List<Integer> codes = errors.stream().map(e -> e.code().code()).distinct().toList();
    log.info("Submission rejected with codes {} for source system {}", codes, source.name());
    try {
      audit.rejected(source.name(), codes);
    } catch (RuntimeException e) {
      log.error("Could not audit a rejected submission", RedactedThrowable.of(e));
    }
  }
}
