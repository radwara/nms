package com.example.nms.routing;

import com.example.nms.common.Channel;
import com.example.nms.common.Severity;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Routing policy (A-26).
 *
 * @param severityEscalation channels added for a severity, e.g. {@code CRITICAL: [SMS]}
 * @param providers the provider of each channel, e.g. {@code SMS: sim-sms}; every channel needs one
 */
@ConfigurationProperties("nms.routing")
record RoutingProperties(
    Map<Severity, List<Channel>> severityEscalation, Map<Channel, String> providers) {

  /** Width of {@code delivery.provider}. */
  static final int MAX_PROVIDER_LENGTH = 32;

  /** Lower-case names, matched exactly (no trimming): they are stored and used in webhook paths. */
  private static final Pattern PROVIDER_NAME = Pattern.compile("[a-z0-9-]+");

  RoutingProperties {
    severityEscalation =
        severityEscalation == null
            ? Map.of()
            : severityEscalation.entrySet().stream()
                .collect(
                    Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    providers = providers == null ? Map.of() : Map.copyOf(providers);
    for (Channel channel : Channel.values()) {
      String provider = providers.get(channel);
      if (provider == null
          || provider.length() > MAX_PROVIDER_LENGTH
          || !PROVIDER_NAME.matcher(provider).matches()) {
        throw new IllegalArgumentException(
            "nms.routing.providers."
                + channel
                + " must be 1 to "
                + MAX_PROVIDER_LENGTH
                + " characters of a-z, 0-9, and '-', e.g. sim-email");
      }
    }
  }

  List<Channel> escalation(Severity severity) {
    return severityEscalation.getOrDefault(severity, List.of());
  }

  String provider(Channel channel) {
    return providers.get(channel);
  }
}
