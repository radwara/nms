package com.example.nms.delivery;

import com.example.nms.common.Channel;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbound rate limit per source system and channel (ADR-0007). Read at startup.
 *
 * @param window length of one fixed window; windows are aligned to multiples of it since the epoch
 * @param perSource sends allowed per source system per window, by channel
 * @param defaultPerSource the limit of a channel not in {@code perSource}
 */
@ConfigurationProperties("nms.rate-limit")
record RateLimitProperties(
    @DefaultValue("1m") Duration window,
    Map<Channel, Integer> perSource,
    @DefaultValue("30") int defaultPerSource) {

  RateLimitProperties {
    require(!window.isNegative() && !window.isZero(), "window");
    perSource = perSource == null ? Map.of() : Map.copyOf(perSource);
    perSource.forEach((channel, limit) -> require(limit > 0, "per-source." + channel));
    require(defaultPerSource > 0, "default-per-source");
  }

  /** Sends allowed per source system per window on {@code channel}. */
  int limit(Channel channel) {
    return perSource.getOrDefault(channel, defaultPerSource);
  }

  private static void require(boolean condition, String property) {
    if (!condition) {
      throw new IllegalArgumentException("nms.rate-limit." + property + " must be positive");
    }
  }
}
