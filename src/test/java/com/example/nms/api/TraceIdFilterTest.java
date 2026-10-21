package com.example.nms.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  @Test
  void takesTraceIdFromValidTraceparent() {
    assertThat(TraceIdFilter.resolve("00-" + TRACE_ID + "-00f067aa0ba902b7-01"))
        .isEqualTo(TRACE_ID);
  }

  @Test
  void acceptsFutureVersionWithExtraFields() {
    assertThat(TraceIdFilter.resolve("01-" + TRACE_ID + "-00f067aa0ba902b7-01-extra"))
        .isEqualTo(TRACE_ID);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "garbage",
        "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
        "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
        "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",
        "00-4bf92f3577b34da6a3ce929d0e0e47-00f067aa0ba902b7-01"
      })
  void generatesTraceIdOtherwise(String traceparent) {
    assertThat(TraceIdFilter.resolve(traceparent)).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
  }

  @Test
  void generatedTraceIdsDiffer() {
    assertThat(TraceIdFilter.resolve(null)).isNotEqualTo(TraceIdFilter.resolve(null));
  }

  @Test
  void setsHeaderAndMdcForTheRequestAndClearsMdcAfterwards() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> mdcInChain = new AtomicReference<>();

    new TraceIdFilter()
        .doFilter(request, response, (req, res) -> mdcInChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

    assertThat(mdcInChain.get()).isEqualTo(TRACE_ID);
    assertThat(response.getHeader(TraceIdFilter.RESPONSE_HEADER)).isEqualTo(TRACE_ID);
    assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
  }
}
