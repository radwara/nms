package com.example.nms.provider;

import com.example.nms.common.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Every {@link ChannelProvider} bean by name. */
@Component
public class ProviderRegistry {

  private final Map<String, ChannelProvider> byName;

  ProviderRegistry(List<ChannelProvider> providers) {
    this.byName =
        providers.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ChannelProvider::name,
                    Function.identity(),
                    (a, b) -> {
                      throw new IllegalStateException("Two provider adapters named " + a.name());
                    }));
  }

  public Optional<ChannelProvider> find(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /**
   * Fails unless every configured provider has an adapter for its channel, so a misconfiguration
   * stops startup instead of leaving deliveries that no worker can send.
   */
  public void requireAll(Map<Channel, String> configured) {
    List<String> problems = new ArrayList<>();
    configured.forEach(
        (channel, name) -> {
          ChannelProvider provider = byName.get(name);
          if (provider == null) {
            problems.add(channel + ": no provider adapter named " + name);
          } else if (provider.channel() != channel) {
            problems.add(channel + ": provider " + name + " serves " + provider.channel());
          }
        });
    if (!problems.isEmpty()) {
      problems.sort(null);
      throw new IllegalStateException(
          "nms.routing.providers: " + String.join("; ", problems) + "; known: " + names());
    }
  }

  private List<String> names() {
    return byName.keySet().stream().sorted().toList();
  }
}
