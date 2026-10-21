package com.example.nms.security;

import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.SIM_EMAIL;
import static com.example.nms.support.TestApiKeys.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DemoCredentialWarningTest {

  private final SecurityConfiguration configuration = new SecurityConfiguration();

  @Test
  void warnsWhenDemoCredentialsAreConfigured(CapturedOutput output) {
    SecurityProperties demo =
        new SecurityProperties(
            "demo1:demo-app:" + sha256Hex(DEMO1), "sim-email:" + sha256Hex(SIM_EMAIL));

    configuration.apiKeyAuthenticator(demo);
    configuration.webhookTokenAuthenticator(demo);

    assertThat(output.getOut())
        .contains("Demo API keys configured (keyIds [demo1])")
        .contains("Demo webhook tokens configured (providers [sim-email])")
        .doesNotContain(SIM_EMAIL, sha256Hex(SIM_EMAIL));
  }

  @Test
  void doesNotWarnForOtherCredentials(CapturedOutput output) {
    SecurityProperties real =
        new SecurityProperties(
            "billing1:billing:" + sha256Hex("nms_billing1_x"),
            "sim-email:" + sha256Hex("another-token"));

    configuration.apiKeyAuthenticator(real);
    configuration.webhookTokenAuthenticator(real);

    assertThat(output.getOut()).doesNotContain("Demo ");
  }
}
