package com.example.nms.api;

import com.example.nms.observability.TraceIds;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the standard error body. Used by filters (before any controller), the exception handler,
 * and the {@code /error} endpoint, so all produce the identical response.
 */
@Component
public class ErrorResponseWriter {

  private static final Logger log = LoggerFactory.getLogger(ErrorResponseWriter.class);

  private final JsonMapper jsonMapper;

  public ErrorResponseWriter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  /** Writes {@code errors} with the HTTP status of their code (all share one status). */
  public void write(HttpServletResponse response, List<ApiError> errors) throws IOException {
    write(response, errors.getFirst().code().status(), errors);
  }

  public void write(HttpServletResponse response, ApiError error) throws IOException {
    write(response, List.of(error));
  }

  /** Writes {@code errors} with an explicit status (framework errors keep their own status). */
  public void write(HttpServletResponse response, HttpStatusCode status, List<ApiError> errors)
      throws IOException {
    if (response.isCommitted()) {
      log.debug("Response already committed; error body not written (status {})", status.value());
      return;
    }
    byte[] body = jsonMapper.writeValueAsBytes(new ErrorBody(TraceIds.current(), errors));
    response.resetBuffer();
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
  }
}
