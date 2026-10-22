package com.example.nms.routing;

import com.example.nms.provider.ProviderRegistry;
import org.springframework.stereotype.Component;

/**
 * Fails startup if a provider in {@code nms.routing.providers} has no adapter bean (or one for
 * another channel), so routing never creates deliveries no worker can send.
 */
@Component
class ProviderCheck {

  ProviderCheck(RoutingProperties policy, ProviderRegistry providers) {
    providers.requireAll(policy.providers());
  }
}
