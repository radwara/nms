package com.example.nms.intake;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #14 review: the dedup lookup reads index entries of matching routed originals only. A storm of
 * suppressed repeats and unrouted rows with the same event key and message is skipped by {@code
 * ix_notification_event_dedup}, not scanned (H2 {@code scanCount}).
 */
@SpringBootTest
class EventDedupLookupIT {

  private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
  private static final String HASH = "c".repeat(64);
  private static final String EVENT = "evt-storm";
  private static final int STORM = 2_000;
  private static final Pattern SCAN_COUNT = Pattern.compile("scanCount: (\\d+)");

  /** The lookup's SQL shape, written out so EXPLAIN can run it. */
  private static final String LOOKUP =
      "select n.recipient_id, n.id from notification n"
          + " where n.source_system = 'demo-app' and n.event_id = ? and n.type = 'FRAUD_ALERT'"
          + " and n.content_hash = ? and n.duplicate_of is null and n.routed = true"
          + " and n.created_at > ? order by n.created_at, n.id";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NotificationRepository notifications;

  private UUID original;

  @BeforeEach
  void stormData() {
    clearSubmissions(jdbc);
    original = UUID.randomUUID();
    List<Object[]> rows = new ArrayList<>();
    rows.add(row(original, EVENT, null, true, NOW.minusSeconds(3_600)));
    for (int i = 0; i < STORM; i++) {
      // Suppressed repeats of the original, unrouted originals, and other events' originals.
      rows.add(row(UUID.randomUUID(), EVENT, original, false, NOW.minusSeconds(i)));
      rows.add(row(UUID.randomUUID(), EVENT, null, false, NOW.minusSeconds(i)));
      rows.add(row(UUID.randomUUID(), EVENT + "-" + i, null, true, NOW.minusSeconds(i)));
    }
    jdbc.batchUpdate(
        "insert into notification (id, submission_id, source_system, event_id, type, severity,"
            + " priority, recipient_id, subject, body, created_at, content_hash, duplicate_of,"
            + " routed) values (?, ?, 'demo-app', ?, 'FRAUD_ALERT', 'INFO', 'NORMAL', 'user-ok',"
            + " 's', 'b', ?, ?, ?, ?)",
        rows);
  }

  @Test
  void findsOnlyTheRoutedOriginal() {
    assertThat(
            notifications.findEventOriginals(
                "demo-app", EVENT, "FRAUD_ALERT", HASH, NOW.minus(Duration.ofHours(24))))
        .containsExactly(new EventOriginal("user-ok", original));
  }

  @Test
  void stormRowsAreSkippedByTheIndex() {
    String plan =
        jdbc.queryForObject(
            "explain analyze " + LOOKUP,
            String.class,
            EVENT,
            HASH,
            Timestamp.from(NOW.minus(Duration.ofHours(24))));

    assertThat(plan).contains("ix_notification_event_dedup");
    Matcher matcher = SCAN_COUNT.matcher(plan);
    assertThat(matcher.find()).as(plan).isTrue();
    assertThat(Long.parseLong(matcher.group(1))).as(plan).isLessThanOrEqualTo(10);
  }

  private static Object[] row(
      UUID id, String event, UUID duplicateOf, boolean routed, Instant createdAt) {
    return new Object[] {
      id, UUID.randomUUID(), event, Timestamp.from(createdAt), HASH, duplicateOf, routed
    };
  }
}
