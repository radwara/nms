package com.example.nms.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The SHA-256 of a credential (API key or webhook token); the plaintext is never held. Matching
 * hashes the presented value and compares in constant time (ADR-0001).
 */
final class HashedSecret {

  /** Matches nothing; used so unknown ids take the same path as wrong secrets. */
  static final HashedSecret NONE = new HashedSecret(new byte[32]);

  private final byte[] sha256;

  private HashedSecret(byte[] sha256) {
    this.sha256 = sha256;
  }

  /** From 64 lowercase hex chars (validated by the config parser). */
  static HashedSecret fromHex(String sha256Hex) {
    return new HashedSecret(HexFormat.of().parseHex(sha256Hex));
  }

  boolean matches(String presented) {
    return MessageDigest.isEqual(sha256, sha256(presented));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JRE", e);
    }
  }

  @Override
  public String toString() {
    return "HashedSecret[redacted]";
  }
}
