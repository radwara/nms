package com.example.nms.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies {@code X-Webhook-Token} against the hashes configured for the provider named in the
 * path. A token configured for another provider does not match. Logs carry the provider only, and
 * only for configured providers.
 */
class WebhookTokenAuthenticator {

  private static final Logger log = LoggerFactory.getLogger(WebhookTokenAuthenticator.class);

  private final Map<String, List<HashedSecret>> secretsByProvider;

  WebhookTokenAuthenticator(List<StoredWebhookToken> tokens) {
    this.secretsByProvider =
        tokens.stream()
            .collect(
                Collectors.groupingBy(
                    StoredWebhookToken::provider,
                    Collectors.mapping(
                        StoredWebhookToken::secret, Collectors.toUnmodifiableList())));
    if (tokens.isEmpty()) {
      log.warn(
          "No webhook tokens configured (NMS_WEBHOOK_TOKENS); every callback will be rejected");
    }
  }

  Optional<WebhookProvider> authenticate(String provider, String presentedToken) {
    List<HashedSecret> secrets =
        secretsByProvider.getOrDefault(provider, List.of(HashedSecret.NONE));
    boolean matches = false;
    for (HashedSecret secret : secrets) {
      matches |= secret.matches(presentedToken); // no short-circuit: same work for every token
    }
    if (!secretsByProvider.containsKey(provider)) {
      log.debug("Webhook token rejected: unknown provider");
      return Optional.empty();
    }
    if (!matches) {
      log.debug("Webhook token rejected: provider={}", provider);
      return Optional.empty();
    }
    return Optional.of(new WebhookProvider(provider));
  }
}
