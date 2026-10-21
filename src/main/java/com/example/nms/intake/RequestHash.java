package com.example.nms.intake;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * SHA-256 (lowercase hex) of a parsed request re-serialized with object keys sorted, so whitespace
 * and key order do not change the hash. Array order is kept: recipient order is part of the
 * payload.
 */
public final class RequestHash {

  private static final JsonMapper MAPPER = JsonMapper.shared();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private RequestHash() {}

  public static String of(JsonNode request) {
    byte[] canonical = MAPPER.writeValueAsBytes(canonical(request));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }

  /** A copy with every object's properties inserted in key order. */
  static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(node.properties());
      properties.sort(Map.Entry.comparingByKey());
      ObjectNode sorted = NODES.objectNode();
      properties.forEach(p -> sorted.set(p.getKey(), canonical(p.getValue())));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode copy = NODES.arrayNode(node.size());
      node.values().forEach(element -> copy.add(canonical(element)));
      return copy;
    }
    return node;
  }
}
