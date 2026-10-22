package com.example.nms.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** #11: enabled simulated callbacks need a token per simulated provider and a valid base-url. */
class SimulatedCallbacksTest {

  @Test
  void enabledWithoutATokenForEveryProviderFailsStartup() {
    SimulatedCallbackProperties properties =
        new SimulatedCallbackProperties(
            true, Duration.ofSeconds(1), null, Map.of("sim-email", "sim-email-dev-only"));

    assertThatThrownBy(() -> new SimulatedCallbacks(properties, Clock.systemUTC()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sim-sms");
  }

  @ParameterizedTest
  @ValueSource(strings = {"localhost:8080", "ftp://localhost", "http://", "http://exa mple", "/x"})
  void enabledWithAnInvalidBaseUrlFailsStartup(String baseUrl) {
    assertThatThrownBy(() -> new SimulatedCallbacks(enabled(baseUrl), Clock.systemUTC()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("base-url");
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {
        "null, null",
        "' ', null",
        "http://localhost:8080, http://localhost:8080",
        "https://nms.example.test/, https://nms.example.test"
      })
  void blankMeansThisServerAndATrailingSlashIsDropped(String baseUrl, String expected) {
    assertThat(SimulatedCallbacks.validBaseUrl(baseUrl)).isEqualTo(expected);
  }

  @Test
  void negativeDelayIsRejected() {
    assertThatThrownBy(
            () -> new SimulatedCallbackProperties(false, Duration.ofMillis(-1), null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SimulatedCallbackProperties enabled(String baseUrl) {
    return new SimulatedCallbackProperties(
        true,
        Duration.ofSeconds(1),
        baseUrl,
        Map.of("sim-email", "sim-email-dev-only", "sim-sms", "sim-sms-dev-only"));
  }
}
