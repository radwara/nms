package com.example.nms.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;

/** Path rules shared by the API-key and webhook-token filters. */
final class SecurityPaths {

  static final String API_PATTERN = "/api/*";
  static final String WEBHOOKS_PREFIX = "/api/v1/webhooks/";
  static final String WEBHOOKS_PATTERN = WEBHOOKS_PREFIX + "*";

  private SecurityPaths() {}

  /** Container-decoded and normalized path, the same one the URL patterns are matched against. */
  static String path(HttpServletRequest request) {
    return request.getServletPath() + Objects.toString(request.getPathInfo(), "");
  }

  static boolean isWebhook(HttpServletRequest request) {
    return path(request).startsWith(WEBHOOKS_PREFIX);
  }

  /**
   * True if the raw (undecoded) URI has a {@code .} or {@code ..} segment, including encoded
   * ({@code %2e}) and path-parameter ({@code ..;x}) forms. Such requests are rejected so the path a
   * filter checks is always the path the client wrote.
   */
  static boolean hasDotSegment(String rawUri) {
    for (String segment : rawUri.split("/", -1)) {
      int params = segment.indexOf(';');
      String name =
          (params >= 0 ? segment.substring(0, params) : segment).replaceAll("(?i)%2e", ".");
      if (name.equals(".") || name.equals("..")) {
        return true;
      }
    }
    return false;
  }
}
