package com.example.nms.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.nms.common.Channel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #10: every configured provider needs an adapter for its channel. */
class ProviderRegistryTest {

  private final ProviderRegistry registry =
      new ProviderRegistry(List.of(new SimulatedEmailProvider(), new SimulatedSmsProvider()));

  @Test
  void findsAdaptersByName() {
    assertThat(registry.find("sim-email")).containsInstanceOf(SimulatedEmailProvider.class);
    assertThat(registry.find("sim-fax")).isEmpty();
  }

  @Test
  void configuredProvidersWithAdaptersPass() {
    assertThatCode(
            () -> registry.requireAll(Map.of(Channel.EMAIL, "sim-email", Channel.SMS, "sim-sms")))
        .doesNotThrowAnyException();
  }

  @Test
  void providerWithoutAdapterFails() {
    assertThatThrownBy(
            () -> registry.requireAll(Map.of(Channel.EMAIL, "sim-email", Channel.SMS, "acme-sms")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMS: no provider adapter named acme-sms");
  }

  @Test
  void adapterForAnotherChannelFails() {
    assertThatThrownBy(() -> registry.requireAll(Map.of(Channel.SMS, "sim-email")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMS: provider sim-email serves EMAIL");
  }

  @Test
  void twoAdaptersWithOneNameFail() {
    assertThatThrownBy(
            () ->
                new ProviderRegistry(
                    List.of(new SimulatedEmailProvider(), new SimulatedEmailProvider())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sim-email");
  }
}
