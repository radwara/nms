package com.example.nms.api;

import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.HEADER;
import static com.example.nms.support.TestApiKeys.SIM_EMAIL;
import static com.example.nms.support.TestApiKeys.WEBHOOK_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.support.ApiTestController;
import com.example.nms.support.FailingTestFilter;
import com.example.nms.support.RawHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #12: the error contract on real Tomcat, where container error dispatch, {@code sendError}, and
 * raw paths behave differently from MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ErrorContractTomcatIT {

  @LocalServerPort private int port;
  @Autowired private JsonMapper jsonMapper;

  @Test
  void missingRequiredHeaderIs4001(CapturedOutput output) throws Exception {
    JsonNode body = assertError(get("/api/v1/test/header"), 400, 4001, "/api/v1/test/header");

    assertThat(body.at("/errors/0/field").asString()).isEqualTo("X-Probe");
  }

  @Test
  void invalidUuidPathVariableIs4003AndTheValueIsNeverLogged(CapturedOutput output)
      throws Exception {
    String value = "not-a-uuid-submitted-value";
    JsonNode body = assertError(get("/api/v1/test/items/" + value), 400, 4003, value);

    assertThat(body.at("/errors/0/field").asString()).isEqualTo("id");
    assertThat(output.getOut()).doesNotContain(value);
  }

  @Test
  void invalidRequestParameterIs4003AndMissingIs4004(CapturedOutput output) throws Exception {
    assertError(get("/api/v1/test/param?count=abc-submitted"), 400, 4003, "abc-submitted");
    assertError(get("/api/v1/test/param"), 400, 4004, "/api/v1/test/param");
    assertThat(output.getOut()).doesNotContain("abc-submitted");
  }

  @Test
  void responseStatusExceptionKeepsItsStatusWithAGenericCode(CapturedOutput output)
      throws Exception {
    assertError(get("/api/v1/test/status/409"), 409, 4000, ApiTestController.STATUS_REASON);
    assertError(get("/api/v1/test/status/404"), 404, 4040, ApiTestController.STATUS_REASON);
    assertError(get("/api/v1/test/status/503"), 503, 5000, ApiTestController.STATUS_REASON);
    assertThat(output.getOut()).doesNotContain(ApiTestController.STATUS_REASON);
  }

  @Test
  void containerSendErrorUsesTheStandardBody(CapturedOutput output) throws Exception {
    assertError(get("/api/v1/test/send-error/503"), 503, 5000, ApiTestController.STATUS_REASON);
    assertError(get("/api/v1/test/send-error/400"), 400, 4000, ApiTestController.STATUS_REASON);
    assertThat(output.getOut()).doesNotContain(ApiTestController.STATUS_REASON);
  }

  @Test
  void exceptionInAFilterIs5000WithoutDetail() throws Exception {
    assertError(get(FailingTestFilter.PATH), 500, 5000, FailingTestFilter.DETAIL);
  }

  @Test
  void directErrorRequestIs4040() throws Exception {
    assertError(get("/error"), 404, 4040, "/error");
  }

  @Test
  void unknownRouteIs4040WithoutEchoingThePath() throws Exception {
    assertError(get("/api/v1/no-such-route-xyz"), 404, 4040, "no-such-route-xyz");
    assertError(get("/no-such-page-xyz"), 404, 4040, "no-such-page-xyz");
  }

  @Test
  void malformedChunkedBodyIsATomcatLevel400WithTheStandardBody(CapturedOutput output)
      throws Exception {
    RawHttp.Response response =
        RawHttp.send(
            port,
            "POST /api/v1/test/echo HTTP/1.1\r\nHost: localhost\r\n"
                + HEADER
                + ": "
                + DEMO1
                + "\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked",
            "zz-not-hex\r\n{\"name\":\"chunk-submitted\"}\r\n0\r\n\r\n");

    assertError(response, 400, 4000, "chunk-submitted");
    assertThat(output.getOut()).doesNotContain("chunk-submitted");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/webhooks/../test/source",
        "/api/v1/webhooks/%2e%2e/test/source",
        "/api/v1/webhooks/%2E%2e;x=1/test/source",
        "/api/v1/./test/source",
        "/api/v1/test/../webhooks/sim-email/test-probe",
        "/api/v1/test/..;x=1/webhooks/sim-email/test-probe",
        "/api/v1/webhooks/sim-email/./test-probe"
      })
  void dotSegmentsAreRejectedBeforeAuthentication(String path) throws Exception {
    RawHttp.Response response =
        RawHttp.send(
            port,
            "POST "
                + path
                + " HTTP/1.1\r\nHost: localhost\r\n"
                + HEADER
                + ": "
                + DEMO1
                + "\r\n"
                + WEBHOOK_HEADER
                + ": "
                + SIM_EMAIL
                + "\r\nContent-Length: 0",
            "");

    assertError(response, 400, 4000, path);
  }

  private RawHttp.Response get(String path) throws Exception {
    return RawHttp.send(
        port, "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n" + HEADER + ": " + DEMO1, "");
  }

  /**
   * Standard body, given status and code, trace id matching the header, no echo of {@code echo}.
   */
  private JsonNode assertError(RawHttp.Response response, int status, int code, String echo) {
    assertThat(response.status()).isEqualTo(status);
    assertThat(response.header("Content-Type")).isEqualTo("application/problem+json");
    assertThat(response.body()).doesNotContain(echo);
    JsonNode body = jsonMapper.readTree(response.body());
    assertThat(body.at("/errors/0/code").asInt()).isEqualTo(code);
    assertThat(response.header("X-Trace-Id")).matches("[0-9a-f]{32}");
    assertThat(body.at("/traceId").asString()).isEqualTo(response.header("X-Trace-Id"));
    return body;
  }
}
