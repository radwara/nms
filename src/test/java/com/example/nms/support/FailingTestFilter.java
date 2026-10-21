package com.example.nms.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Test-only filter that throws on one path, to exercise the container's error dispatch. */
@Component
public class FailingTestFilter extends OncePerRequestFilter {

  public static final String PATH = "/test-filter-failure";
  public static final String DETAIL = "filter-detail-must-not-leak";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !PATH.equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
    throw new IllegalStateException(DETAIL);
  }
}
