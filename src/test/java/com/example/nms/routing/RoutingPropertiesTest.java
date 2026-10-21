package com.example.nms.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.Severity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

/** #9: the routing policy binds from configuration and fails fast when incomplete. */
class RoutingPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(RoutingConfiguration.class);

  @Test
  void bindsEscalationAndProviders() {
    runner
        .withPropertyValues(
            "nms.routing.severity-escalation.CRITICAL=SMS,EMAIL",
            "nms.routing.providers.EMAIL=mail-a",
            "nms.routing.providers.SMS=sms-b")
        .run(
            context -> {
              RoutingProperties properties = context.getBean(RoutingProperties.class);
              assertThat(properties.escalation(Severity.CRITICAL))
                  .containsExactly(Channel.SMS, Channel.EMAIL);
              assertThat(properties.escalation(Severity.INFO)).isEmpty();
              assertThat(properties.provider(Channel.EMAIL)).isEqualTo("mail-a");
              assertThat(properties.provider(Channel.SMS)).isEqualTo("sms-b");
            });
  }

  @Test
  void startupFailsWhenAChannelHasNoProvider() {
    runner
        .withPropertyValues("nms.routing.providers.EMAIL=sim-email")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("nms.routing.providers.SMS"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " sim-sms", "sim-sms ", "Sim-SMS", "sim_sms", "sim.sms", "sms/1"})
  void startupFailsOnAProviderNameOutsideTheAllowedCharacters(String name) {
    assertRejected(name);
  }

  @Test
  void startupFailsWhenAProviderNameIsTooLong() {
    assertRejected("s".repeat(RoutingProperties.MAX_PROVIDER_LENGTH + 1));
  }

  @Test
  void acceptsTheLongestAllowedName() {
    String name = "a-1".repeat(10) + "zz";
    runner
        .withPropertyValues(
            "nms.routing.providers.EMAIL=sim-email", "nms.routing.providers.SMS=" + name)
        .run(
            context ->
                assertThat(context.getBean(RoutingProperties.class).provider(Channel.SMS))
                    .isEqualTo(name));
  }

  /** Binds the value verbatim: {@code withPropertyValues} would trim it. */
  private void assertRejected(String smsProvider) {
    runner
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new MapPropertySource(
                            "verbatim",
                            Map.of(
                                "nms.routing.providers.EMAIL",
                                "sim-email",
                                "nms.routing.providers.SMS",
                                smsProvider))))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("nms.routing.providers.SMS")
                    .hasMessageContaining("a-z, 0-9, and '-'"));
  }
}
