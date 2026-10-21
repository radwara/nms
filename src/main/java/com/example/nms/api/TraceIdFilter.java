package com.example.nms.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter on every request: takes the trace id from a valid W3C {@code traceparent} or
 * generates one, puts it in the MDC, and returns it in {@code X-Trace-Id}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String MDC_KEY = "traceId";
  public static final String RESPONSE_HEADER = "X-Trace-Id";
  static final String TRACEPARENT_HEADER = "traceparent";

  // version-traceid-parentid-flags; future versions may append fields (W3C Trace Context).
  private static final Pattern TRACEPARENT =
      Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}(-.*)?");
  private static final String ZERO_TRACE_ID = "0".repeat(32);
  private static final String ZERO_PARENT_ID = "0".repeat(16);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = resolve(request.getHeader(TRACEPARENT_HEADER));
    MDC.put(MDC_KEY, traceId);
    response.setHeader(RESPONSE_HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** Trace id from a valid {@code traceparent}, otherwise a new random one (32 lowercase hex). */
  static String resolve(String traceparent) {
    if (traceparent != null) {
      Matcher m = TRACEPARENT.matcher(traceparent.trim());
      if (m.matches()
          && !"ff".equals(m.group(1))
          && !("00".equals(m.group(1)) && m.group(4) != null)
          && !ZERO_TRACE_ID.equals(m.group(2))
          && !ZERO_PARENT_ID.equals(m.group(3))) {
        return m.group(2);
      }
    }
    return generate();
  }

  private static String generate() {
    byte[] bytes = new byte[16];
    ThreadLocalRandom.current().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
