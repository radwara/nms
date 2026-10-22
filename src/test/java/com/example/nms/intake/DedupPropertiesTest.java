package com.example.nms.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** #14, A-39: dedup settings bind with their defaults and a window that cannot work fails. */
class DedupPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(IntakeConfiguration.class);

  @Test
  void defaultsToEnabledWithA24HourWindow() {
    runner.run(
        context -> {
          DedupProperties properties = context.getBean(DedupProperties.class);
          assertThat(properties.enabled()).isTrue();
          assertThat(properties.window()).isEqualTo(Duration.ofHours(24));
        });
  }

  @Test
  void bindsTheOffSwitchAndWindow() {
    runner
        .withPropertyValues("nms.dedup.enabled=false", "nms.dedup.window=1h")
        .run(
            context -> {
              DedupProperties properties = context.getBean(DedupProperties.class);
              assertThat(properties.enabled()).isFalse();
              assertThat(properties.window()).isEqualTo(Duration.ofHours(1));
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"0s", "-1h"})
  void startupFailsOnANonPositiveWindow(String window) {
    runner
        .withPropertyValues("nms.dedup.window=" + window)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessage("nms.dedup.window must be positive"));
  }
}
