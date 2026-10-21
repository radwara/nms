package com.example.nms.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code nms.security.api-keys} and {@code nms.security.webhook-tokens}. Any malformed entry
 * fails startup with a message that names the entry by position (and id when readable), never by
 * hash.
 */
final class CredentialsConfigParser {

  static final String KEY_ID_REGEX = "[A-Za-z0-9]{1,32}";
  static final String PROVIDER_REGEX = "[a-z0-9-]{1,32}";
  private static final String SHA256_HEX = "([0-9a-f]{64})";

  private static final Pattern API_KEY =
      Pattern.compile("(" + KEY_ID_REGEX + "):([A-Za-z0-9._-]{1,64}):" + SHA256_HEX);
  private static final Pattern WEBHOOK_TOKEN =
      Pattern.compile("(" + PROVIDER_REGEX + "):" + SHA256_HEX);

  private static final String API_KEYS = "nms.security.api-keys (NMS_API_KEYS)";
  private static final String WEBHOOK_TOKENS = "nms.security.webhook-tokens (NMS_WEBHOOK_TOKENS)";

  private CredentialsConfigParser() {}

  static List<StoredApiKey> apiKeys(String raw) {
    Set<String> keyIds = new HashSet<>();
    return parse(
        raw,
        API_KEYS,
        API_KEY,
        "expected keyId:sourceSystem:sha256hex (keyId [A-Za-z0-9]{1,32}, sourceSystem"
            + " [A-Za-z0-9._-]{1,64}, sha256hex 64 lowercase hex chars)",
        m -> {
          if (!keyIds.add(m.group(1))) {
            throw new IllegalArgumentException("duplicate keyId '" + m.group(1) + "'");
          }
          return new StoredApiKey(m.group(1), m.group(2), HashedSecret.fromHex(m.group(3)));
        });
  }

  /** Several tokens per provider are allowed (rotation). */
  static List<StoredWebhookToken> webhookTokens(String raw) {
    return parse(
        raw,
        WEBHOOK_TOKENS,
        WEBHOOK_TOKEN,
        "expected provider:sha256hex (provider [a-z0-9-]{1,32}, sha256hex 64 lowercase hex chars)",
        m -> new StoredWebhookToken(m.group(1), HashedSecret.fromHex(m.group(2))));
  }

  private static <T> List<T> parse(
      String raw,
      String property,
      Pattern entry,
      String expected,
      Function<Matcher, T> toCredential) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<T> credentials = new ArrayList<>();
    String[] entries = raw.split(",", -1);
    for (int i = 0; i < entries.length; i++) {
      Matcher m = entry.matcher(entries[i].trim());
      if (!m.matches()) {
        throw invalid(property, i, expected);
      }
      try {
        credentials.add(toCredential.apply(m));
      } catch (IllegalArgumentException e) {
        throw invalid(property, i, e.getMessage());
      }
    }
    return List.copyOf(credentials);
  }

  private static IllegalArgumentException invalid(String property, int index, String reason) {
    return new IllegalArgumentException(
        "Invalid " + property + " entry " + (index + 1) + ": " + reason);
  }
}
