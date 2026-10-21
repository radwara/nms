package com.example.nms.api;

import com.example.nms.observability.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter on every request: takes the trace id from a valid W3C {@code traceparent} or
 * generates one, binds it to the MDC, and returns it in {@code X-Trace-Id}. Also runs on the error
 * dispatch, reusing the request's id, so {@code /error} responses and logs carry the same id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String RESPONSE_HEADER = "X-Trace-Id";
  static final String TRACEPARENT_HEADER = "traceparent";
  private static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

  // version-traceid-parentid-flags; future versions may append fields (W3C Trace Context).
  private static final Pattern TRACEPARENT =
      Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}(-.*)?");
  private static final String ZERO_TRACE_ID = "0".repeat(32);
  private static final String ZERO_PARENT_ID = "0".repeat(16);

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId =
        request.getAttribute(REQUEST_ATTRIBUTE) instanceof String existing
            ? existing
            : resolve(request.getHeader(TRACEPARENT_HEADER));
    request.setAttribute(REQUEST_ATTRIBUTE, traceId);
    response.setHeader(RESPONSE_HEADER, traceId);
    TraceIds.Scope scope = TraceIds.open(traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      scope.close();
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
    return TraceIds.newId();
  }
}
