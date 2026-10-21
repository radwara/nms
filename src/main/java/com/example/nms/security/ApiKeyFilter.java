package com.example.nms.security;

import com.example.nms.api.ApiError;
import com.example.nms.api.ErrorCode;
import com.example.nms.api.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /api/**} requests by {@code X-API-Key} and exposes the {@link SourceSystem}.
 * Webhooks are excluded: they use their own token.
 */
class ApiKeyFilter extends OncePerRequestFilter {

  static final String HEADER = "X-API-Key";
  static final String URL_PATTERN = "/api/*";
  static final String CHALLENGE = "ApiKey header=\"" + HEADER + "\"";
  private static final String WEBHOOKS_PREFIX = "/api/v1/webhooks/";

  private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

  private final ApiKeyAuthenticator authenticator;
  private final ErrorResponseWriter errorWriter;

  ApiKeyFilter(ApiKeyAuthenticator authenticator, ErrorResponseWriter errorWriter) {
    this.authenticator = authenticator;
    this.errorWriter = errorWriter;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Container-decoded and normalized path, the same one the URL pattern was matched against.
    String path = request.getServletPath() + Objects.toString(request.getPathInfo(), "");
    return path.startsWith(WEBHOOKS_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    if (key == null || key.isBlank()) {
      log.warn("API key missing");
      reject(response, new ApiError(ErrorCode.CREDENTIALS_MISSING, HEADER, "API key is missing"));
      return;
    }
    Optional<SourceSystem> sourceSystem = authenticator.authenticate(key);
    if (sourceSystem.isEmpty()) {
      reject(response, new ApiError(ErrorCode.CREDENTIALS_INVALID, HEADER, "API key is invalid"));
      return;
    }
    request.setAttribute(SourceSystem.REQUEST_ATTRIBUTE, sourceSystem.get());
    chain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response, ApiError error) throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
    errorWriter.write(response, error);
  }
}
