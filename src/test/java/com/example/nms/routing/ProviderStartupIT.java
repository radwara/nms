package com.example.nms.routing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.nms.NmsApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** #10 (from #9 review): a configured provider without an adapter bean stops startup. */
class ProviderStartupIT {

  @Test
  void providerWithoutAdapterFailsStartup() {
    assertThatThrownBy(() -> start("nms.routing.providers.SMS=acme-sms"))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMS: no provider adapter named acme-sms");
  }

  @Test
  void adapterForAnotherChannelFailsStartup() {
    assertThatThrownBy(() -> start("nms.routing.providers.SMS=sim-email"))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMS: provider sim-email serves EMAIL");
  }

  /** {@code property} as a command-line argument, so it overrides {@code application.yml}. */
  private static void start(String property) {
    new SpringApplicationBuilder(NmsApplication.class)
        .web(WebApplicationType.NONE)
        .run("--" + property)
        .close();
  }
}
