package com.example.nms.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** #14: dedup fields of a new notification. */
class NotificationTest {

  private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

  @Test
  void aNewRowRequiresAContentHash() {
    assertThatThrownBy(() -> notification(null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("contentHash");
  }

  @Test
  void anOriginalCanBeMarkedRouted() {
    Notification original = notification("a".repeat(64), null);
    assertThat(original.isRouted()).isFalse();

    original.markRouted();

    assertThat(original.isRouted()).isTrue();
  }

  @Test
  void aSuppressedRowIsNeverRouted() {
    Notification suppressed = notification("a".repeat(64), UUID.randomUUID());

    assertThatThrownBy(suppressed::markRouted).isInstanceOf(IllegalStateException.class);
    assertThat(suppressed.isRouted()).isFalse();
  }

  private static Notification notification(String contentHash, UUID duplicateOf) {
    return new Notification(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "demo-app",
        "evt-1",
        "FRAUD_ALERT",
        Severity.INFO,
        Priority.NORMAL,
        "user-ok",
        null,
        "subject",
        "body",
        null,
        null,
        NOW,
        contentHash,
        duplicateOf);
  }
}
