package com.example.nms.api;

import static com.example.nms.support.ApiAssertions.assertSingleError;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** #12: standard error body for framework and application errors. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ErrorContractIT {

  private static final String ECHO = "/api/v1/test/echo";

  @Autowired private MockMvcTester mvc;

  @Test
  void malformedJsonIs4002WithoutField() {
    MvcTestResult result =
        mvc.post()
            .uri(ECHO)
            .header(HEADER, DEMO1)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\": \"secret-value\"")
            .exchange();

    assertSingleError(result, 400, 4002, null);
    assertThat(result).bodyText().doesNotContain("secret-value");
  }

  @Test
  void wrongJsonTypeIs4003WithTheJsonPath() {
    MvcTestResult result =
        mvc.post()
            .uri("/api/v1/test/typed")
            .header(HEADER, DEMO1)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"count\": 1, \"items\": [{\"qty\": 2}, {\"qty\": \"many-submitted\"}]}")
            .exchange();

    assertSingleError(result, 400, 4003, "items[1].qty");
    assertThat(result).bodyText().doesNotContain("many-submitted");
  }

  @Test
  void wrongJsonShapeAtTheRootIs4003WithoutField() {
    MvcTestResult result =
        mvc.post()
            .uri("/api/v1/test/typed")
            .header(HEADER, DEMO1)
            .contentType(MediaType.APPLICATION_JSON)
            .content("[1, 2]")
            .exchange();

    assertSingleError(result, 400, 4003, null);
  }

  @Test
  void missingBodyIs4002() {
    assertSingleError(
        mvc.post()
            .uri(ECHO)
            .header(HEADER, DEMO1)
            .contentType(MediaType.APPLICATION_JSON)
            .exchange(),
        400,
        4002,
        null);
  }

  @Test
  void missingHeaderIs4001AndMissingParameterIs4004() {
    assertSingleError(
        mvc.get().uri("/api/v1/test/header").header(HEADER, DEMO1).exchange(),
        400,
        4001,
        "X-Probe");
    assertSingleError(
        mvc.get().uri("/api/v1/test/param").header(HEADER, DEMO1).exchange(), 400, 4004, "count");
  }

  @Test
  void wellFormedJsonIsAccepted() {
    assertThat(
            mvc.post()
                .uri(ECHO)
                .header(HEADER, DEMO1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"probe\"}"))
        .hasStatusOk();
  }

  @Test
  void unsupportedContentTypeIs4151() {
    MvcTestResult result =
        mvc.post()
            .uri(ECHO)
            .header(HEADER, DEMO1)
            .contentType(MediaType.TEXT_PLAIN)
            .content("name=probe")
            .exchange();

    assertSingleError(result, 415, 4151, "Content-Type");
    assertThat(result).containsHeader("Accept");
  }

  @Test
  void unknownRouteIs4040WithoutEchoingThePath() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/test/no-such-route-xyz").header(HEADER, DEMO1).exchange();

    assertSingleError(result, 404, 4040, null);
    assertThat(result).bodyText().doesNotContain("no-such-route-xyz");
  }

  @Test
  void unknownRouteOutsideTheApiIs4040() {
    assertSingleError(mvc.get().uri("/no-such-page").exchange(), 404, 4040, null);
  }

  @Test
  void wrongMethodIs4050WithAllowHeader() {
    MvcTestResult result = mvc.get().uri(ECHO).header(HEADER, DEMO1).exchange();

    assertSingleError(result, 405, 4050, null);
    assertThat(result).hasHeader("Allow", "POST");
  }

  @Test
  void unacceptableResponseTypeIs4060() {
    MvcTestResult result =
        mvc.get()
            .uri("/api/v1/test/source")
            .header(HEADER, DEMO1)
            .accept(MediaType.APPLICATION_XML)
            .exchange();

    assertSingleError(result, 406, 4060, "Accept");
  }

  @Test
  void unexpectedExceptionIs5000WithNoDetailAndIsLoggedWithTraceId(CapturedOutput output) {
    MvcTestResult result =
        mvc.get().uri("/api/v1/test/unexpected").header(HEADER, DEMO1).exchange();

    assertSingleError(result, 500, 5000, null);
    assertThat(result).bodyJson().extractingPath("$.errors[0].message").isEqualTo("Internal error");
    assertThat(result)
        .bodyText()
        .doesNotContain(ApiTestController.UNEXPECTED_DETAIL, "IllegalStateException");

    String traceId = result.getResponse().getHeader(TraceIdFilter.RESPONSE_HEADER);
    assertThat(output.getOut().lines())
        .anySatisfy(
            line ->
                assertThat(line)
                    .contains(
                        "Request failed with status 500",
                        "\"traceId\":\"" + traceId + "\"",
                        "IllegalStateException"));
    assertThat(output.getOut()).doesNotContain(ApiTestController.UNEXPECTED_DETAIL);
  }

  @Test
  void apiExceptionReturnsEveryError() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/test/api-exception").header(HEADER, DEMO1).exchange();

    assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.errors.length()").isEqualTo(2);
    assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo(4004);
    assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("eventId");
    assertThat(result).bodyJson().extractingPath("$.errors[1].code").isEqualTo(4003);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[1].field")
        .isEqualTo("recipients[0].userId");
  }
}
