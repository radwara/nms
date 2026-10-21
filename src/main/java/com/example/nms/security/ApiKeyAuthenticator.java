package com.example.nms.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies {@code nms_<keyId>_<secret>} keys against configured SHA-256 hashes of the full key
 * (ADR-0001). Unknown keyId and wrong secret take the same path. Logs carry the keyId only, and
 * only for configured keyIds, so a mistyped secret is never logged.
 */
class ApiKeyAuthenticator {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

  // keyId has no underscore; the secret is everything after the second underscore.
  private static final Pattern PRESENTED_KEY =
      Pattern.compile("nms_(" + CredentialsConfigParser.KEY_ID_REGEX + ")_.+");

  private final Map<String, StoredApiKey> keysById;

  ApiKeyAuthenticator(List<StoredApiKey> keys) {
    this.keysById =
        keys.stream()
            .collect(Collectors.toUnmodifiableMap(StoredApiKey::keyId, Function.identity()));
    if (keys.isEmpty()) {
      log.warn("No API keys configured (NMS_API_KEYS); every /api request will be rejected");
    }
  }

  Optional<SourceSystem> authenticate(String presentedKey) {
    Matcher m = PRESENTED_KEY.matcher(presentedKey);
    if (!m.matches()) {
      log.debug("API key rejected: malformed");
      return Optional.empty();
    }
    StoredApiKey stored = keysById.get(m.group(1));
    boolean matches = (stored == null ? HashedSecret.NONE : stored.secret()).matches(presentedKey);
    if (stored == null) {
      log.debug("API key rejected: unknown keyId");
      return Optional.empty();
    }
    if (!matches) {
      log.debug("API key rejected: keyId={}", stored.keyId());
      return Optional.empty();
    }
    log.debug("API key accepted: keyId={}", stored.keyId());
    return Optional.of(new SourceSystem(stored.sourceSystem()));
  }
}
