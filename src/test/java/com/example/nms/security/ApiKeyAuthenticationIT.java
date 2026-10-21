package com.example.nms.security;

import static com.example.nms.support.ApiAssertions.assertSingleError;
import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.DEMO2;
import static com.example.nms.support.TestApiKeys.HEADER;
import static com.example.nms.support.TestApiKeys.OTHER1;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** #12: API-key authentication per ADR-0001. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyAuthenticationIT {

  private static final String SOURCE = "/api/v1/test/source";

  @Autowired private MockMvcTester mvc;
  @Autowired private JsonMapper jsonMapper;

  @Test
  void validKeyAuthenticatesAsItsSourceSystem() {
    assertThat(mvc.get().uri(SOURCE).header(HEADER, DEMO1))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.sourceSystem")
        .isEqualTo("demo-app");
    assertThat(mvc.get().uri(SOURCE).header(HEADER, OTHER1))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.sourceSystem")
        .isEqualTo("other-app");
  }

  @Test
  void bothKeysOfOneSourceSystemWork() {
    assertThat(mvc.get().uri(SOURCE).header(HEADER, DEMO2))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.sourceSystem")
        .isEqualTo("demo-app");
  }

  @Test
  void missingKeyIs4011() {
    assertUnauthorized(mvc.get().uri(SOURCE).exchange(), 4011);
    assertUnauthorized(mvc.get().uri(SOURCE).header(HEADER, " ").exchange(), 4011);
  }

  @Test
  void unknownKeyIdIs4012() {
    assertUnauthorized(get("nms_nosuchkeyid_dev-only"), 4012);
  }

  @Test
  void wrongSecretIs4012WithTheSameBodyAsUnknownKeyId() {
    MvcTestResult wrongSecret = get("nms_demo1_wrong-secret");
    MvcTestResult unknownKeyId = get("nms_nosuchkeyid_dev-only");

    assertUnauthorized(wrongSecret, 4012);
    assertThat(withoutTraceId(wrongSecret)).isEqualTo(withoutTraceId(unknownKeyId));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "dev-only",
        "nms_demo1",
        "nms_demo1_",
        "nms__dev-only",
        "NMS_demo1_dev-only",
        "Bearer nms_demo1_dev-only",
        "nms_demo-1_dev-only"
      })
  void malformedKeyIs4012(String key) {
    assertUnauthorized(get(key), 4012);
  }

  @Test
  void secretMayContainUnderscoreButMustMatchExactly() {
    assertUnauthorized(get(DEMO1 + "_"), 4012);
  }

  @Test
  void actuatorHealthNeedsNoKey() {
    assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
  }

  @Test
  void webhooksAreNotBlockedByTheApiKeyFilter() {
    MvcTestResult result = mvc.post().uri("/api/v1/webhooks/sim-email").exchange();

    assertSingleError(result, 404, 4040, null);
    assertThat(result).doesNotContainHeader("WWW-Authenticate");
  }

  @Test
  void logsNeverContainKeySecretsOrHashes(CapturedOutput output) {
    assertThat(get(DEMO1)).hasStatusOk();
    assertThat(get("nms_demo1_wrong-secret")).hasStatus(401);
    assertThat(get("nms_nosuchkeyid_another-secret")).hasStatus(401);
    assertThat(get("not-a-key-at-all")).hasStatus(401);

    assertThat(output.getOut())
        .contains("API key rejected: keyId=demo1", "API key rejected: unknown keyId")
        .doesNotContain(
            "dev-only",
            "wrong-secret",
            "another-secret",
            "nosuchkeyid",
            "not-a-key-at-all",
            "4530bfad54b2432bdec5406b7f9f668987e2697b931ed715f965f6c02aec995b");
  }

  private static void assertUnauthorized(MvcTestResult result, int code) {
    assertSingleError(result, 401, code, HEADER);
    assertThat(result).hasHeader("WWW-Authenticate", "ApiKey header=\"X-API-Key\"");
  }

  private MvcTestResult get(String key) {
    return mvc.get().uri(SOURCE).header(HEADER, key).exchange();
  }

  private JsonNode withoutTraceId(MvcTestResult result) {
    ObjectNode body =
        (ObjectNode) jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    body.remove("traceId");
    return body;
  }
}
