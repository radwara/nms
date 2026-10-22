package com.example.nms.provider;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Callbacks of the simulated vendors (ADR-0003). Dev and demo only.
 *
 * @param enabled whether accepted sends are followed by a callback; off in tests that drive
 *     callbacks directly
 * @param delay time between acceptance and the callback
 * @param baseUrl where the webhook is served; blank means this server, {@code
 *     http://localhost:<port>}
 * @param tokens plaintext webhook token per provider: the documented demo tokens (ADR-0001), never
 *     real secrets
 */
@ConfigurationProperties("nms.simulator.callbacks")
record SimulatedCallbackProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("1s") Duration delay,
    String baseUrl,
    Map<String, String> tokens) {

  SimulatedCallbackProperties {
    if (delay.isNegative()) {
      throw new IllegalArgumentException("nms.simulator.callbacks.delay must be >= 0");
    }
    tokens = tokens == null ? Map.of() : Map.copyOf(tokens);
  }

  @Override
  public String toString() {
    return "SimulatedCallbackProperties[enabled="
        + enabled
        + ", delay="
        + delay
        + ", baseUrl="
        + baseUrl
        + ", tokens for "
        + tokens.keySet()
        + "]";
  }
}
