package com.example.nms.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the standard error body. Used by filters (before any controller) and by the exception
 * handler, so both produce the identical response.
 */
@Component
public class ErrorResponseWriter {

  private final JsonMapper jsonMapper;

  public ErrorResponseWriter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  /** Writes {@code errors} with the HTTP status of their code (all share one status). */
  public void write(HttpServletResponse response, List<ApiError> errors) throws IOException {
    byte[] body =
        jsonMapper.writeValueAsBytes(new ErrorBody(MDC.get(TraceIdFilter.MDC_KEY), errors));
    response.setStatus(errors.getFirst().code().status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
  }

  public void write(HttpServletResponse response, ApiError error) throws IOException {
    write(response, List.of(error));
  }
}
