package com.example.nms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyConfigParserTest {

  private static final String HASH_1 =
      "4530bfad54b2432bdec5406b7f9f668987e2697b931ed715f965f6c02aec995b";
  private static final String HASH_2 =
      "3a83b1837de2e6b4cda334d357681460a2289f1fae9702354878758b2a79e807";

  @Test
  void parsesEntriesIncludingSeveralForOneSourceSystem() {
    List<StoredApiKey> keys =
        ApiKeyConfigParser.parse("demo1:demo-app:" + HASH_1 + ", demo2:demo-app:" + HASH_2);

    assertThat(keys).extracting(StoredApiKey::keyId).containsExactly("demo1", "demo2");
    assertThat(keys).extracting(StoredApiKey::sourceSystem).containsOnly("demo-app");
    assertThat(HexFormat.of().formatHex(keys.getFirst().sha256())).isEqualTo(HASH_1);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  "})
  void emptyConfigurationMeansNoKeys(String raw) {
    assertThat(ApiKeyConfigParser.parse(raw)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "demo1:demo-app",
        "demo1:demo-app:" + HASH_1 + ":extra",
        "demo_1:demo-app:" + HASH_1,
        "demo1::" + HASH_1,
        "demo1:demo app:" + HASH_1,
        "demo1:demo-app:4530BFAD54B2432BDEC5406B7F9F668987E2697B931ED715F965F6C02AEC995B",
        "demo1:demo-app:4530bfad",
        "demo1:demo-app:" + HASH_1 + ",",
        "demo1:demo-app:" + HASH_1 + ",,demo2:demo-app:" + HASH_2
      })
  void malformedEntryFailsWithAClearMessageThatDoesNotEchoTheHash(String raw) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ApiKeyConfigParser.parse(raw))
        .withMessageStartingWith("Invalid nms.security.api-keys (NMS_API_KEYS) entry ")
        .withMessageContaining("expected keyId:sourceSystem:sha256hex")
        .withMessageNotContaining(HASH_1)
        .withMessageNotContaining("4530");
  }

  @Test
  void malformedEntryIsIdentifiedByPosition() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ApiKeyConfigParser.parse("demo1:demo-app:" + HASH_1 + ",broken"))
        .withMessageStartingWith("Invalid nms.security.api-keys (NMS_API_KEYS) entry 2:");
  }

  @Test
  void duplicateKeyIdFails() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ApiKeyConfigParser.parse("demo1:demo-app:" + HASH_1 + ",demo1:other:" + HASH_2))
        .withMessage(
            "Invalid nms.security.api-keys (NMS_API_KEYS) entry 2: duplicate keyId 'demo1'");
  }
}
