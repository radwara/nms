package com.example.nms.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code nms.security.api-keys}. Any malformed entry fails startup with a message that names
 * the entry by position (and keyId when readable), never by hash.
 */
final class ApiKeyConfigParser {

  static final String KEY_ID_REGEX = "[A-Za-z0-9]{1,32}";

  private static final Pattern ENTRY =
      Pattern.compile("(" + KEY_ID_REGEX + "):([A-Za-z0-9._-]{1,64}):([0-9a-f]{64})");
  private static final String EXPECTED =
      "expected keyId:sourceSystem:sha256hex (keyId [A-Za-z0-9]{1,32}, sourceSystem"
          + " [A-Za-z0-9._-]{1,64}, sha256hex 64 lowercase hex chars)";

  private ApiKeyConfigParser() {}

  static List<StoredApiKey> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<StoredApiKey> keys = new ArrayList<>();
    Set<String> keyIds = new HashSet<>();
    String[] entries = raw.split(",", -1);
    for (int i = 0; i < entries.length; i++) {
      Matcher m = ENTRY.matcher(entries[i].trim());
      if (!m.matches()) {
        throw invalid(i, EXPECTED);
      }
      String keyId = m.group(1);
      if (!keyIds.add(keyId)) {
        throw invalid(i, "duplicate keyId '" + keyId + "'");
      }
      keys.add(new StoredApiKey(keyId, m.group(2), HexFormat.of().parseHex(m.group(3))));
    }
    return List.copyOf(keys);
  }

  private static IllegalArgumentException invalid(int index, String reason) {
    return new IllegalArgumentException(
        "Invalid nms.security.api-keys (NMS_API_KEYS) entry " + (index + 1) + ": " + reason);
  }
}
