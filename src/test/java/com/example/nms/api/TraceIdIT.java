package com.example.nms.api;

import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.support.ApiTestController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** #12: trace id from traceparent or generated; in header, error body, and logs. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TraceIdIT {

  private static final String SOURCE = "/api/v1/test/source";
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

  @Autowired private MockMvcTester mvc;

  @Test
  void traceparentIsHonoured() {
    MvcTestResult result = mvc.get().uri(SOURCE).header("traceparent", TRACEPARENT).exchange();

    assertThat(result).hasStatus(401).hasHeader(TraceIdFilter.RESPONSE_HEADER, TRACE_ID);
    assertThat(result).bodyJson().extractingPath("$.traceId").isEqualTo(TRACE_ID);
  }

  @Test
  void invalidTraceparentIsReplacedByAGeneratedId() {
    MvcTestResult result =
        mvc.get().uri(SOURCE).header("traceparent", "00-" + TRACE_ID + "-bad-01").exchange();

    assertThat(traceId(result)).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
  }

  @Test
  void generatedTraceIdIs32HexAndMatchesTheErrorBody() {
    MvcTestResult result = mvc.get().uri(SOURCE).exchange();

    assertThat(traceId(result)).matches("[0-9a-f]{32}");
    assertThat(result).bodyJson().extractingPath("$.traceId").isEqualTo(traceId(result));
  }

  @Test
  void everyResponseCarriesATraceId() {
    assertThat(traceId(mvc.get().uri(SOURCE).header(HEADER, DEMO1).exchange()))
        .matches("[0-9a-f]{32}");
    assertThat(traceId(mvc.get().uri("/actuator/health").exchange())).matches("[0-9a-f]{32}");
  }

  @Test
  void traceIdIsInTheJsonLogLine(CapturedOutput output) {
    mvc.get()
        .uri("/api/v1/test/log")
        .header(HEADER, DEMO1)
        .header("traceparent", TRACEPARENT)
        .exchange();

    String line =
        output
            .getOut()
            .lines()
            .filter(l -> l.contains(ApiTestController.LOG_PROBE))
            .findFirst()
            .orElseThrow();
    assertThat(line).startsWith("{").contains("\"traceId\":\"" + TRACE_ID + "\"");
  }

  private static String traceId(MvcTestResult result) {
    return result.getResponse().getHeader(TraceIdFilter.RESPONSE_HEADER);
  }
}
