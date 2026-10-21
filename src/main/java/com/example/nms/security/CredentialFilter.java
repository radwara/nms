package com.example.nms.security;

import com.example.nms.api.ApiError;
import com.example.nms.api.ErrorCode;
import com.example.nms.api.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared flow of the credential filters: reject dot segments (400/4000), require the header
 * (401/4011), verify it (401/4012), and expose the principal as a request attribute. Every 401
 * carries the filter's {@code WWW-Authenticate} challenge.
 */
abstract class CredentialFilter<P> extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(CredentialFilter.class);

  private final ErrorResponseWriter errorWriter;
  private final String header;
  private final String label;
  private final String principalAttribute;
  private final String challenge;

  CredentialFilter(
      ErrorResponseWriter errorWriter,
      String header,
      String label,
      String scheme,
      String principalAttribute) {
    this.errorWriter = errorWriter;
    this.header = header;
    this.label = label;
    this.principalAttribute = principalAttribute;
    this.challenge = scheme + " header=\"" + header + "\"";
  }

  /** The principal for a valid credential, else empty. */
  abstract Optional<P> authenticate(HttpServletRequest request, String credential);

  @Override
  protected final void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (SecurityPaths.hasDotSegment(request.getRequestURI())) {
      log.debug("Request rejected: dot segment in path");
      errorWriter.write(response, ApiError.of(ErrorCode.BAD_REQUEST));
      return;
    }
    String credential = request.getHeader(header);
    if (credential == null || credential.isBlank()) {
      log.debug("{} missing", label);
      reject(response, new ApiError(ErrorCode.CREDENTIALS_MISSING, header, label + " is missing"));
      return;
    }
    Optional<P> principal = authenticate(request, credential);
    if (principal.isEmpty()) {
      reject(response, new ApiError(ErrorCode.CREDENTIALS_INVALID, header, label + " is invalid"));
      return;
    }
    request.setAttribute(principalAttribute, principal.get());
    chain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response, ApiError error) throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
    errorWriter.write(response, error);
  }
}
