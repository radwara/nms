package com.example.nms.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Assertions for the standard error body. */
public final class ApiAssertions {

  private ApiAssertions() {}

  /** One error with {@code code} and {@code field} ({@code null}: field must be absent). */
  public static void assertSingleError(MvcTestResult result, int status, int code, String field) {
    assertThat(result)
        .hasStatus(status)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .containsHeader("X-Trace-Id");
    assertThat(result).bodyJson().extractingPath("$.errors.length()").isEqualTo(1);
    assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo(code);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.traceId")
        .isEqualTo(result.getResponse().getHeader("X-Trace-Id"));
    if (field == null) {
      assertThat(result).bodyJson().doesNotHavePath("$.errors[0].field");
    } else {
      assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo(field);
    }
  }
}
