package com.example.nms.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

  @Test
  void startupFailsWhenAProviderNameIsTooLong() {
    runner
        .withPropertyValues(
            "nms.routing.providers.EMAIL=sim-email", "nms.routing.providers.SMS=" + "s".repeat(33))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("nms.routing.providers.SMS"));
  }
}
