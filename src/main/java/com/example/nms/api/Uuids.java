package com.example.nms.api;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Strict UUID parsing for ids from paths and bodies. */
final class Uuids {

  /** Length of the canonical form, {@code 8-4-4-4-12} hex digits. */
  static final int TEXT_LENGTH = 36;

  private Uuids() {}

  /**
   * The UUID in canonical form (either case); empty otherwise. {@link UUID#fromString} alone also
   * accepts short forms such as {@code 1-1-1-1-1}.
   */
  static Optional<UUID> parse(String text) {
    if (text == null || text.length() != TEXT_LENGTH) {
      return Optional.empty();
    }
    try {
      UUID uuid = UUID.fromString(text);
      return uuid.toString().equals(text.toLowerCase(Locale.ROOT))
          ? Optional.of(uuid)
          : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
