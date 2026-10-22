package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** #46: rate limit settings bind from configuration and fail fast when not positive. */
class RateLimitPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Bind.class);

  @Test
  void bindsWindowPerChannelLimitsAndDefault() {
    runner
        .withPropertyValues(
            "nms.rate-limit.window=10s",
            "nms.rate-limit.per-source.EMAIL=5",
            "nms.rate-limit.default-per-source=2")
        .run(
            context -> {
              RateLimitProperties properties = context.getBean(RateLimitProperties.class);
              assertThat(properties.window()).isEqualTo(Duration.ofSeconds(10));
              assertThat(properties.limit(Channel.EMAIL)).isEqualTo(5);
              assertThat(properties.limit(Channel.SMS)).as("unlisted channel").isEqualTo(2);
            });
  }

  @Test
  void withoutSettingsTheWindowIsOneMinuteAndEveryChannelGetsTheDefault() {
    runner.run(
        context -> {
          RateLimitProperties properties = context.getBean(RateLimitProperties.class);
          assertThat(properties.window()).isEqualTo(Duration.ofMinutes(1));
          assertThat(properties.limit(Channel.EMAIL)).isEqualTo(30);
          assertThat(properties.limit(Channel.SMS)).isEqualTo(30);
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"0s", "-1s"})
  void startupFailsOnAWindowThatIsNotPositive(String window) {
    assertRejected("nms.rate-limit.window=" + window, "nms.rate-limit.window must be positive");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void startupFailsOnAChannelLimitThatIsNotPositive(int limit) {
    assertRejected(
        "nms.rate-limit.per-source.SMS=" + limit, "nms.rate-limit.per-source.SMS must be positive");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void startupFailsOnADefaultThatIsNotPositive(int limit) {
    assertRejected(
        "nms.rate-limit.default-per-source=" + limit,
        "nms.rate-limit.default-per-source must be positive");
  }

  @Test
  void startupFailsOnAnUnknownChannel() {
    runner
        .withPropertyValues("nms.rate-limit.per-source.FAX=5")
        .run(context -> assertThat(context).hasFailed());
  }

  private void assertRejected(String property, String message) {
    runner
        .withPropertyValues(property)
        .run(
            context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessage(message));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RateLimitProperties.class)
  static class Bind {}
}
