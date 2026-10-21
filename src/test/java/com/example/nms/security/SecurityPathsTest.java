package com.example.nms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityPathsTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/webhooks/../notifications",
        "/api/v1/./notifications",
        "/api/v1/webhooks/%2e%2e/notifications",
        "/api/v1/webhooks/%2E%2e/notifications",
        "/api/v1/webhooks/%2e/notifications",
        "/api/v1/webhooks/..;x=1/notifications",
        "/api/v1/webhooks/sim-email/..",
        "/.."
      })
  void detectsDotSegments(String uri) {
    assertThat(SecurityPaths.hasDotSegment(uri)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/notifications",
        "/api/v1/notifications/6f1c.json",
        "/api/v1/webhooks/sim-email",
        "/api/v1/a..b/c",
        "/api/v1/...",
        "/api/v1/notifications;x=1"
      })
  void acceptsOrdinaryPaths(String uri) {
    assertThat(SecurityPaths.hasDotSegment(uri)).isFalse();
  }
}
