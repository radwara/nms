package com.example.nms.security;

import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.DEMO2;
import static com.example.nms.support.TestApiKeys.SIM_EMAIL;
import static com.example.nms.support.TestApiKeys.SIM_SMS;
import static com.example.nms.support.TestApiKeys.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CredentialsConfigParserTest {

  private static final String HASH_1 = sha256Hex(DEMO1);
  private static final String HASH_2 = sha256Hex(DEMO2);
  private static final String API_KEYS_PREFIX =
      "Invalid nms.security.api-keys (NMS_API_KEYS) entry ";
  private static final String WEBHOOK_PREFIX =
      "Invalid nms.security.webhook-tokens (NMS_WEBHOOK_TOKENS) entry ";

  @Test
  void parsesApiKeysIncludingSeveralForOneSourceSystem() {
    List<StoredApiKey> keys =
        CredentialsConfigParser.apiKeys("demo1:demo-app:" + HASH_1 + ", demo2:demo-app:" + HASH_2);

    assertThat(keys).extracting(StoredApiKey::keyId).containsExactly("demo1", "demo2");
    assertThat(keys).extracting(StoredApiKey::sourceSystem).containsOnly("demo-app");
    assertThat(keys.getFirst().secret().matches(DEMO1)).isTrue();
    assertThat(keys.getFirst().secret().matches(DEMO2)).isFalse();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  "})
  void emptyConfigurationMeansNoCredentials(String raw) {
    assertThat(CredentialsConfigParser.apiKeys(raw)).isEmpty();
    assertThat(CredentialsConfigParser.webhookTokens(raw)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "demo1:demo-app",
        "demo1:demo-app:HASH:extra",
        "demo_1:demo-app:HASH",
        "demo1::HASH",
        "demo1:demo app:HASH",
        "demo1:demo-app:UPPER",
        "demo1:demo-app:4530bfad",
        "demo1:demo-app:HASH,",
        "demo1:demo-app:HASH,,demo2:demo-app:HASH"
      })
  void malformedApiKeyFailsWithAClearMessageThatDoesNotEchoTheHash(String template) {
    String raw = template.replace("HASH", HASH_1).replace("UPPER", HASH_1.toUpperCase());

    assertThatIllegalArgumentException()
        .isThrownBy(() -> CredentialsConfigParser.apiKeys(raw))
        .withMessageStartingWith(API_KEYS_PREFIX)
        .withMessageContaining("expected keyId:sourceSystem:sha256hex")
        .withMessageNotContaining(HASH_1)
        .withMessageNotContaining(HASH_1.substring(0, 8));
  }

  @Test
  void malformedEntryIsIdentifiedByPosition() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CredentialsConfigParser.apiKeys("demo1:demo-app:" + HASH_1 + ",broken"))
        .withMessageStartingWith(API_KEYS_PREFIX + "2:");
  }

  @Test
  void duplicateKeyIdFails() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                CredentialsConfigParser.apiKeys(
                    "demo1:demo-app:" + HASH_1 + ",demo1:other:" + HASH_2))
        .withMessage(API_KEYS_PREFIX + "2: duplicate keyId 'demo1'");
  }

  @Test
  void parsesWebhookTokensIncludingSeveralForOneProvider() {
    List<StoredWebhookToken> tokens =
        CredentialsConfigParser.webhookTokens(
            "sim-email:" + sha256Hex(SIM_EMAIL) + ",sim-email:" + sha256Hex(SIM_SMS));

    assertThat(tokens).extracting(StoredWebhookToken::provider).containsOnly("sim-email");
    assertThat(tokens.get(0).secret().matches(SIM_EMAIL)).isTrue();
    assertThat(tokens.get(1).secret().matches(SIM_SMS)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sim-email",
        "sim_email:HASH",
        "Sim-Email:HASH",
        ":HASH",
        "sim-email:x",
        "a:HASH,"
      })
  void malformedWebhookTokenFailsWithAClearMessage(String template) {
    String raw = template.replace("HASH", HASH_1);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> CredentialsConfigParser.webhookTokens(raw))
        .withMessageStartingWith(WEBHOOK_PREFIX)
        .withMessageContaining("expected provider:sha256hex")
        .withMessageNotContaining(HASH_1);
  }
}
