package com.example.nms.intake;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Event-level deduplication (ADR-0006, A-39).
 *
 * @param enabled {@code false} restores greenfield behaviour: no lookup, nothing suppressed; the
 *     content hash is still written, so re-enabling works at once
 * @param window a repeat is suppressed while its original is younger than this (strict)
 */
@ConfigurationProperties("nms.dedup")
record DedupProperties(
    @DefaultValue("true") boolean enabled, @DefaultValue("24h") Duration window) {

  DedupProperties {
    if (window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("nms.dedup.window must be positive");
    }
  }
}
