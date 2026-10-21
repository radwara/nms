package com.example.nms.security;

import static com.example.nms.support.ApiAssertions.assertSingleError;
import static com.example.nms.support.TestApiKeys.SIM_EMAIL;
import static com.example.nms.support.TestApiKeys.SIM_SMS;
import static com.example.nms.support.TestApiKeys.WEBHOOK_HEADER;
import static com.example.nms.support.TestApiKeys.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * #12: webhook-token authentication (fail-closed, token bound to the path's provider). Dot-segment
 * rejection needs raw paths and is covered on real Tomcat in {@code ErrorContractTomcatIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class WebhookTokenAuthenticationIT {

  private static final String EMAIL_PROBE = "/api/v1/webhooks/sim-email/test-probe";
  private static final String CHALLENGE = "Token header=\"X-Webhook-Token\"";

  @Autowired private MockMvcTester mvc;

  @Test
  void validTokenReachesTheEndpointAsItsProvider() {
    assertThat(mvc.post().uri(EMAIL_PROBE).header(WEBHOOK_HEADER, SIM_EMAIL))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.provider")
        .isEqualTo("sim-email");
    assertThat(
            mvc.post().uri("/api/v1/webhooks/sim-sms/test-probe").header(WEBHOOK_HEADER, SIM_SMS))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.provider")
        .isEqualTo("sim-sms");
  }

  @Test
  void missingTokenIs4011WithTokenChallenge() {
    assertRejected(mvc.post().uri(EMAIL_PROBE).exchange(), 4011);
    assertRejected(mvc.post().uri(EMAIL_PROBE).header(WEBHOOK_HEADER, " ").exchange(), 4011);
  }

  @Test
  void wrongTokenIs4012() {
    assertRejected(post(EMAIL_PROBE, "wrong-submitted-token"), 4012);
  }

  @Test
  void tokenOfAnotherProviderIs4012() {
    assertRejected(post(EMAIL_PROBE, SIM_SMS), 4012);
  }

  @Test
  void unknownProviderIs4012() {
    assertRejected(post("/api/v1/webhooks/no-such-provider/test-probe", SIM_EMAIL), 4012);
  }

  @Test
  void logsNeverContainTokensOrHashes(CapturedOutput output) {
    assertThat(post(EMAIL_PROBE, SIM_EMAIL)).hasStatusOk();
    assertThat(post(EMAIL_PROBE, "wrong-submitted-token")).hasStatus(401);

    assertThat(output.getOut())
        .doesNotContain(SIM_EMAIL, "wrong-submitted-token", sha256Hex(SIM_EMAIL));
  }

  private MvcTestResult post(String path, String token) {
    return mvc.post().uri(path).header(WEBHOOK_HEADER, token).exchange();
  }

  private static void assertRejected(MvcTestResult result, int code) {
    assertSingleError(result, 401, code, WEBHOOK_HEADER);
    assertThat(result).hasHeader("WWW-Authenticate", CHALLENGE);
  }
}
