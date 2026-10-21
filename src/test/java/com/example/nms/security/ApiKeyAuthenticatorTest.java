package com.example.nms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyAuthenticatorTest {

  // SHA-256 of the full keys nms_demo1_dev-only, nms_demo2_dev-only, and nms_under_sc_re.
  private static final ApiKeyAuthenticator AUTHENTICATOR =
      new ApiKeyAuthenticator(
          ApiKeyConfigParser.parse(
              "demo1:demo-app:4530bfad54b2432bdec5406b7f9f668987e2697b931ed715f965f6c02aec995b,"
                  + "demo2:demo-app:3a83b1837de2e6b4cda334d357681460a2289f1fae9702354878758b2a79e807,"
                  + "under:other-app:3140e1461098878b3a0827d4b20e5da4e9fe790ca04f8637d8f3e37ed0d4d43a"));

  @Test
  void validKeysResolveToTheirSourceSystem() {
    assertThat(AUTHENTICATOR.authenticate("nms_demo1_dev-only"))
        .contains(new SourceSystem("demo-app"));
    assertThat(AUTHENTICATOR.authenticate("nms_demo2_dev-only"))
        .contains(new SourceSystem("demo-app"));
  }

  @Test
  void secretMayContainUnderscores() {
    assertThat(AUTHENTICATOR.authenticate("nms_under_sc_re"))
        .contains(new SourceSystem("other-app"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "nms_demo1_wrong",
        "nms_demo2_dev-only ",
        "nms_unknown_dev-only",
        "nms_demo1_",
        "nms_demo1",
        "nms__dev-only",
        "demo1_dev-only",
        ""
      })
  void anythingElseIsRejected(String key) {
    assertThat(AUTHENTICATOR.authenticate(key)).isEmpty();
  }

  @Test
  void noConfiguredKeysRejectsEverything() {
    assertThat(new ApiKeyAuthenticator(List.of()).authenticate("nms_demo1_dev-only")).isEmpty();
  }
}
