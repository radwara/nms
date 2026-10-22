package com.example.nms.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.support.TestApiKeys;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #14, ADR-0006: the message part of the event dedup key. */
class ContentHashTest {

  private static final Instant AT = Instant.parse("2026-10-21T10:00:00Z");

  @Test
  void isTheSha256OfTheCanonicalJson() {
    String canonical = "{\"body\":\"b\",\"severity\":\"CRITICAL\",\"subject\":\"s\"}";

    assertThat(ContentHash.canonical(command("s", "b", Severity.CRITICAL)).toString())
        .isEqualTo(canonical);
    assertThat(ContentHash.of(command("s", "b", Severity.CRITICAL)))
        .isEqualTo(TestApiKeys.sha256Hex(canonical))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void ignoresEverythingButSubjectBodyAndSeverity() {
    String hash = ContentHash.of(command("s", "b", Severity.CRITICAL));
    SubmitCommand other =
        new SubmitCommand(
            "other-app",
            "other-key",
            "other-event",
            "OTHER_TYPE",
            Severity.CRITICAL,
            Priority.LOW,
            List.of(
                new SubmitCommand.Recipient("user-b", List.of(Channel.SMS)),
                new SubmitCommand.Recipient("user-a", null)),
            "s",
            "b",
            AT,
            AT.plusSeconds(60));

    assertThat(ContentHash.of(other)).isEqualTo(hash);
  }

  @Test
  void changesWithSubjectBodyOrSeverity() {
    String hash = ContentHash.of(command("s", "b", Severity.CRITICAL));

    assertThat(ContentHash.of(command("s2", "b", Severity.CRITICAL))).isNotEqualTo(hash);
    assertThat(ContentHash.of(command("s", "b2", Severity.CRITICAL))).isNotEqualTo(hash);
    assertThat(ContentHash.of(command("s", "b", Severity.WARNING))).isNotEqualTo(hash);
    // Exactly as validated: no trimming or case folding.
    assertThat(ContentHash.of(command("s ", "b", Severity.CRITICAL))).isNotEqualTo(hash);
    assertThat(ContentHash.of(command("S", "b", Severity.CRITICAL))).isNotEqualTo(hash);
  }

  @Test
  void fieldBoundariesAreUnambiguous() {
    assertThat(ContentHash.of(command("ab", "c", Severity.INFO)))
        .isNotEqualTo(ContentHash.of(command("a", "bc", Severity.INFO)));
  }

  private static SubmitCommand command(String subject, String body, Severity severity) {
    return new SubmitCommand(
        "demo-app",
        "key-1",
        "evt-1",
        "FRAUD_ALERT",
        severity,
        Priority.HIGH,
        List.of(new SubmitCommand.Recipient("user-a", null)),
        subject,
        body,
        null,
        null);
  }
}
