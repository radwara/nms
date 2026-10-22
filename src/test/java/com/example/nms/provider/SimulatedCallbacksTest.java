package com.example.nms.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #11: enabled simulated callbacks need a token for every simulated provider. */
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

  @Test
  void negativeDelayIsRejected() {
    assertThatThrownBy(
            () -> new SimulatedCallbackProperties(false, Duration.ofMillis(-1), null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
