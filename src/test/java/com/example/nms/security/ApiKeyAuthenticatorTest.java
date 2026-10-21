package com.example.nms.security;

import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.DEMO2;
import static com.example.nms.support.TestApiKeys.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyAuthenticatorTest {

  private static final String UNDERSCORED = "nms_under_sc_re";

  private static final ApiKeyAuthenticator AUTHENTICATOR =
      new ApiKeyAuthenticator(
          CredentialsConfigParser.apiKeys(
              "demo1:demo-app:"
                  + sha256Hex(DEMO1)
                  + ",demo2:demo-app:"
                  + sha256Hex(DEMO2)
                  + ",under:other-app:"
                  + sha256Hex(UNDERSCORED)));

  @Test
  void validKeysResolveToTheirSourceSystem() {
    assertThat(AUTHENTICATOR.authenticate(DEMO1)).contains(new SourceSystem("demo-app"));
    assertThat(AUTHENTICATOR.authenticate(DEMO2)).contains(new SourceSystem("demo-app"));
  }

  @Test
  void secretMayContainUnderscores() {
    assertThat(AUTHENTICATOR.authenticate(UNDERSCORED)).contains(new SourceSystem("other-app"));
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
    assertThat(new ApiKeyAuthenticator(List.of()).authenticate(DEMO1)).isEmpty();
  }
}
