package com.example.nms.security;

import static com.example.nms.support.TestApiKeys.SIM_EMAIL;
import static com.example.nms.support.TestApiKeys.SIM_SMS;
import static com.example.nms.support.TestApiKeys.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookTokenAuthenticatorTest {

  private static final String ROTATED = "sim-email-rotated";

  private static final WebhookTokenAuthenticator AUTHENTICATOR =
      new WebhookTokenAuthenticator(
          CredentialsConfigParser.webhookTokens(
              "sim-email:"
                  + sha256Hex(SIM_EMAIL)
                  + ",sim-email:"
                  + sha256Hex(ROTATED)
                  + ",sim-sms:"
                  + sha256Hex(SIM_SMS)));

  @Test
  void everyTokenOfAProviderWorks() {
    assertThat(AUTHENTICATOR.authenticate("sim-email", SIM_EMAIL))
        .contains(new WebhookProvider("sim-email"));
    assertThat(AUTHENTICATOR.authenticate("sim-email", ROTATED))
        .contains(new WebhookProvider("sim-email"));
  }

  @Test
  void tokenIsBoundToItsProvider() {
    assertThat(AUTHENTICATOR.authenticate("sim-email", SIM_SMS)).isEmpty();
    assertThat(AUTHENTICATOR.authenticate("sim-sms", SIM_EMAIL)).isEmpty();
  }

  @Test
  void unknownProviderOrWrongTokenIsRejected() {
    assertThat(AUTHENTICATOR.authenticate("other", SIM_EMAIL)).isEmpty();
    assertThat(AUTHENTICATOR.authenticate("", SIM_EMAIL)).isEmpty();
    assertThat(AUTHENTICATOR.authenticate("sim-email", "wrong")).isEmpty();
  }

  @Test
  void noConfiguredTokensRejectsEverything() {
    assertThat(new WebhookTokenAuthenticator(List.of()).authenticate("sim-email", SIM_EMAIL))
        .isEmpty();
  }
}
