package com.example.nms.intake;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.delivery.DeliveryWorker;
import com.example.nms.intake.SubmittedNotification.Outcome;
import com.example.nms.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #14, ADR-0006: a repeat of the same event and message for a recipient under a new key, within the
 * window, is suppressed: a row without deliveries, audited on both rows. Anything else is
 * delivered.
 */
@SpringBootTest
@Import(EventDedupIT.ClockConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EventDedupIT {

  private static final Duration WINDOW = Duration.ofHours(24);
  private static final Duration MICRO = Duration.ofNanos(1_000);
  private static final String SUBJECT = "Dedup subject " + UUID.randomUUID();
  private static final String BODY = "Dedup body " + UUID.randomUUID();

  @Autowired private IntakeService intake;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MutableClock clock;
  @Autowired private NotificationRepository notifications;
  @Autowired private DeliveryWorker worker;

  private String event;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
    event = "evt-dedup-" + UUID.randomUUID();
  }

  @Test
  void repeatWithinTheWindowIsSuppressedAndAuditedOnBothRows(CapturedOutput output) {
    SubmittedNotification original = single(intake.submit(command("user-ok")));
    clock.advance(Duration.ofMinutes(10));

    SubmittedNotification repeat = single(intake.submit(command("user-ok")));

    assertThat(original.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(repeat.outcome()).isEqualTo(Outcome.SUPPRESSED);
    assertThat(repeat.duplicateOf()).isEqualTo(original.notificationId());
    assertThat(repeat.notificationId()).isNotEqualTo(original.notificationId());
    Notification row = notifications.findById(repeat.notificationId()).orElseThrow();
    assertThat(row.getDuplicateOf()).isEqualTo(original.notificationId());
    assertThat(row.getContentHash())
        .isEqualTo(notifications.findById(original.notificationId()).orElseThrow().getContentHash())
        .hasSize(64);
    assertThat(deliveries(repeat.notificationId())).isZero();
    assertThat(deliveries(original.notificationId())).isEqualTo(2);
    assertThat(audit(repeat.notificationId()))
        .containsExactly("SUPPRESSED duplicate of " + original.notificationId());
    assertThat(audit(original.notificationId())).first().isEqualTo("ACCEPTED null");
    assertThat(audit(original.notificationId()))
        .last()
        .isEqualTo("DUPLICATE_EVENT suppressed " + repeat.notificationId());
    assertThat(output.getOut()).contains("accepted: 0 notifications, 1 suppressed");

    // Leak check: no content and no content hash in logs or audit details.
    String hash = row.getContentHash();
    assertThat(output.getAll()).doesNotContain(SUBJECT, BODY, hash);
    assertThat(
            jdbc.queryForList(
                "select detail from audit_event where detail is not null", String.class))
        .noneMatch(d -> d.contains(SUBJECT) || d.contains(BODY) || d.contains(hash));
  }

  @Test
  void sameEventWithADifferentMessageIsDelivered() {
    intake.submit(command("user-ok"));

    assertThat(outcome(withMessage(command("user-ok"), SUBJECT + " (2)", BODY, Severity.CRITICAL)))
        .isEqualTo(Outcome.ACCEPTED);
    assertThat(outcome(withMessage(command("user-ok"), SUBJECT, BODY + " (2)", Severity.CRITICAL)))
        .isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  void severityUpgradeIsDelivered() {
    assertThat(outcome(withMessage(command("user-ok"), SUBJECT, BODY, Severity.WARNING)))
        .isEqualTo(Outcome.ACCEPTED);

    assertThat(outcome(withMessage(command("user-ok"), SUBJECT, BODY, Severity.CRITICAL)))
        .isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  void differentTypeRecipientOrSourceSystemIsDelivered() {
    intake.submit(command("user-ok"));

    assertThat(outcome(withType(command("user-ok"), "OTHER_ALERT"))).isEqualTo(Outcome.ACCEPTED);
    assertThat(outcome(command("user-email-only"))).isEqualTo(Outcome.ACCEPTED);
    assertThat(outcome(withSource(command("user-ok"), "other-app"))).isEqualTo(Outcome.ACCEPTED);
    assertThat(outcome(withEvent(command("user-ok"), event + "-other")))
        .isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  void windowIsStrictAndAnchoredOnTheOriginal() {
    SubmittedNotification original = single(intake.submit(command("user-ok")));

    clock.advance(WINDOW.minus(MICRO));
    SubmittedNotification justInside = single(intake.submit(command("user-ok")));
    clock.advance(MICRO);
    SubmittedNotification exactlyWindow = single(intake.submit(command("user-ok")));

    assertThat(justInside.duplicateOf()).isEqualTo(original.notificationId());
    assertThat(exactlyWindow.outcome()).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  void suppressedRowsDoNotExtendTheWindow() {
    SubmittedNotification original = single(intake.submit(command("user-ok")));

    clock.advance(Duration.ofHours(20));
    assertThat(single(intake.submit(command("user-ok"))).duplicateOf())
        .isEqualTo(original.notificationId());
    clock.advance(Duration.ofHours(5));

    assertThat(outcome(command("user-ok"))).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  void notRoutedOriginalDoesNotAnchor() {
    // No phone: requested SMS only leaves no usable channel, so nothing was ever sent.
    SubmitCommand smsOnly = withChannels(command("user-email-only"), List.of(Channel.SMS));
    SubmittedNotification notRouted = single(intake.submit(smsOnly));
    assertThat(deliveries(notRouted.notificationId())).isZero();
    assertThat(routed(notRouted.notificationId())).isFalse();

    // Resent with a usable channel (e.g. after the request or directory is fixed): delivered.
    SubmittedNotification resend = single(intake.submit(command("user-email-only")));
    assertThat(resend.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(deliveries(resend.notificationId())).isOne();
    assertThat(routed(resend.notificationId())).isTrue();

    // The routed resend anchors the next repeat.
    assertThat(single(intake.submit(command("user-email-only"))).duplicateOf())
        .isEqualTo(resend.notificationId());
  }

  @Test
  void channelsAreNotPartOfTheKey() {
    SubmittedNotification original =
        single(intake.submit(withChannels(command("user-ok"), List.of(Channel.EMAIL))));

    SubmittedNotification repeat = single(intake.submit(command("user-ok")));

    assertThat(repeat.duplicateOf()).isEqualTo(original.notificationId());
  }

  @Test
  void failedOriginalStillAnchors() {
    // Both channels are rejected permanently by the simulated vendors.
    SubmittedNotification original = single(intake.submit(command("user-reject")));
    worker.runOnce();
    assertThat(deliveryStatuses(original.notificationId())).containsOnly("FAILED");

    assertThat(single(intake.submit(command("user-reject"))).duplicateOf())
        .isEqualTo(original.notificationId());
  }

  @Test
  void expiredOriginalStillAnchors() {
    SubmittedNotification original =
        single(intake.submit(withExpiresAt(command("user-ok"), now().plus(Duration.ofMinutes(1)))));
    clock.advance(Duration.ofMinutes(2));
    worker.runOnce();
    assertThat(deliveryStatuses(original.notificationId())).containsOnly("EXPIRED");

    // expiresAt is not part of the message.
    SubmitCommand resend = withExpiresAt(command("user-ok"), now().plus(Duration.ofHours(1)));
    assertThat(single(intake.submit(resend)).duplicateOf()).isEqualTo(original.notificationId());
  }

  @Test
  void rowsWithoutAContentHashNeverMatch() {
    // Written like a pre-V3 row: the greenfield constructor stores no content hash.
    notifications.saveAndFlush(
        new Notification(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "demo-app",
            event,
            "FRAUD_ALERT",
            Severity.CRITICAL,
            Priority.HIGH,
            "user-ok",
            null,
            SUBJECT,
            BODY,
            null,
            null,
            now().minus(Duration.ofHours(1))));

    assertThat(outcome(command("user-ok"))).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  void mixedBatchKeepsRequestOrderAndRoutesOnlyOriginals() {
    SubmittedNotification original = single(intake.submit(command("user-ok")));

    List<SubmittedNotification> batch =
        created(intake.submit(command("user-email-only", "user-ok", "user-sms-optout")));

    assertThat(batch)
        .extracting(SubmittedNotification::recipientId)
        .containsExactly("user-email-only", "user-ok", "user-sms-optout");
    assertThat(batch)
        .extracting(SubmittedNotification::outcome)
        .containsExactly(Outcome.ACCEPTED, Outcome.SUPPRESSED, Outcome.ACCEPTED);
    assertThat(batch.get(1).duplicateOf()).isEqualTo(original.notificationId());
    for (SubmittedNotification n : List.of(batch.get(0), batch.get(2))) {
      assertThat(audit(n.notificationId())).first().isEqualTo("ACCEPTED null");
      assertThat(audit(n.notificationId())).anyMatch(e -> e.startsWith("ROUTED"));
      assertThat(deliveries(n.notificationId())).isPositive();
    }
    assertThat(audit(batch.get(1).notificationId())).hasSize(1);
  }

  @Test
  void fullySuppressedSubmissionIsCreatedWithNoRouting() {
    intake.submit(command("user-ok", "user-email-only"));
    int deliveriesBefore = jdbc.queryForObject("select count(*) from delivery", Integer.class);

    SubmitResult result = intake.submit(command("user-ok", "user-email-only"));

    assertThat(result).isInstanceOf(SubmitResult.Created.class);
    assertThat(created(result))
        .extracting(SubmittedNotification::outcome)
        .containsOnly(Outcome.SUPPRESSED);
    assertThat(jdbc.queryForObject("select count(*) from delivery", Integer.class))
        .isEqualTo(deliveriesBefore);
    for (SubmittedNotification n : created(result)) {
      assertThat(audit(n.notificationId())).singleElement().asString().startsWith("SUPPRESSED");
    }
  }

  @Test
  void replayOfAMixedBatchReturnsTheSameOutcomesAndSuppressesNothingNew() {
    intake.submit(command("user-ok"));
    SubmitCommand batch = command("user-email-only", "user-ok");
    List<SubmittedNotification> first = created(intake.submit(batch));
    clock.advance(WINDOW.plusHours(1)); // a replay stays SUPPRESSED after the window

    SubmitResult replay = intake.submit(batch);

    assertThat(replay).isInstanceOf(SubmitResult.Replayed.class);
    assertThat(((SubmitResult.Replayed) replay).notifications()).isEqualTo(first);
    for (SubmittedNotification n : first) {
      assertThat(audit(n.notificationId())).last().asString().startsWith("DUPLICATE_SUBMISSION");
    }
    assertThat(count("SUPPRESSED")).isOne();
    assertThat(count("DUPLICATE_EVENT")).isOne();
  }

  @Test
  void idempotencyAndValidationComeBeforeDedup() {
    SubmitCommand original = command("user-ok");
    intake.submit(original);

    // Same key, different payload: 409 even though the event repeats.
    SubmitCommand sameKey =
        withKey(withType(command("user-ok"), "OTHER_ALERT"), original.idempotencyKey());
    assertThat(intake.submit(sameKey)).isInstanceOf(SubmitResult.KeyConflict.class);
    // A repeat with an unknown recipient: 422, nothing written, nothing suppressed.
    assertThat(intake.submit(command("user-ok", "user-unknown")))
        .isInstanceOf(SubmitResult.Unprocessable.class);

    assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).isOne();
    assertThat(count("SUPPRESSED")).isZero();
  }

  /** A new key each time; same event and message within one test. */
  private SubmitCommand command(String... userIds) {
    return new SubmitCommand(
        "demo-app",
        "key-" + UUID.randomUUID(),
        event,
        "FRAUD_ALERT",
        Severity.CRITICAL,
        Priority.HIGH,
        Arrays.stream(userIds).map(id -> new SubmitCommand.Recipient(id, null)).toList(),
        SUBJECT,
        BODY,
        null,
        null);
  }

  private static SubmitCommand withMessage(
      SubmitCommand c, String subject, String body, Severity severity) {
    return new SubmitCommand(
        c.sourceSystem(),
        c.idempotencyKey(),
        c.eventId(),
        c.type(),
        severity,
        c.priority(),
        c.recipients(),
        subject,
        body,
        c.scheduledAt(),
        c.expiresAt());
  }

  private static SubmitCommand withType(SubmitCommand c, String type) {
    return new SubmitCommand(
        c.sourceSystem(),
        c.idempotencyKey(),
        c.eventId(),
        type,
        c.severity(),
        c.priority(),
        c.recipients(),
        c.subject(),
        c.body(),
        c.scheduledAt(),
        c.expiresAt());
  }

  private static SubmitCommand withSource(SubmitCommand c, String sourceSystem) {
    return new SubmitCommand(
        sourceSystem,
        c.idempotencyKey(),
        c.eventId(),
        c.type(),
        c.severity(),
        c.priority(),
        c.recipients(),
        c.subject(),
        c.body(),
        c.scheduledAt(),
        c.expiresAt());
  }

  private static SubmitCommand withEvent(SubmitCommand c, String eventId) {
    return new SubmitCommand(
        c.sourceSystem(),
        c.idempotencyKey(),
        eventId,
        c.type(),
        c.severity(),
        c.priority(),
        c.recipients(),
        c.subject(),
        c.body(),
        c.scheduledAt(),
        c.expiresAt());
  }

  private static SubmitCommand withKey(SubmitCommand c, String key) {
    return new SubmitCommand(
        c.sourceSystem(),
        key,
        c.eventId(),
        c.type(),
        c.severity(),
        c.priority(),
        c.recipients(),
        c.subject(),
        c.body(),
        c.scheduledAt(),
        c.expiresAt());
  }

  private static SubmitCommand withExpiresAt(SubmitCommand c, Instant expiresAt) {
    return new SubmitCommand(
        c.sourceSystem(),
        c.idempotencyKey(),
        c.eventId(),
        c.type(),
        c.severity(),
        c.priority(),
        c.recipients(),
        c.subject(),
        c.body(),
        c.scheduledAt(),
        expiresAt);
  }

  private static SubmitCommand withChannels(SubmitCommand c, List<Channel> channels) {
    return new SubmitCommand(
        c.sourceSystem(),
        c.idempotencyKey(),
        c.eventId(),
        c.type(),
        c.severity(),
        c.priority(),
        c.recipients().stream()
            .map(r -> new SubmitCommand.Recipient(r.userId(), channels))
            .toList(),
        c.subject(),
        c.body(),
        c.scheduledAt(),
        c.expiresAt());
  }

  private Outcome outcome(SubmitCommand command) {
    return single(intake.submit(command)).outcome();
  }

  private static List<SubmittedNotification> created(SubmitResult result) {
    assertThat(result).isInstanceOf(SubmitResult.Created.class);
    return ((SubmitResult.Created) result).notifications();
  }

  private static SubmittedNotification single(SubmitResult result) {
    List<SubmittedNotification> created = created(result);
    assertThat(created).hasSize(1);
    return created.getFirst();
  }

  private int deliveries(UUID notificationId) {
    return jdbc.queryForObject(
        "select count(*) from delivery where notification_id = ?", Integer.class, notificationId);
  }

  private boolean routed(UUID notificationId) {
    return jdbc.queryForObject(
        "select routed from notification where id = ?", Boolean.class, notificationId);
  }

  private List<String> deliveryStatuses(UUID notificationId) {
    return jdbc.queryForList(
        "select status from delivery where notification_id = ?", String.class, notificationId);
  }

  /** {@code "TYPE detail"} per event, in history order. */
  private List<String> audit(UUID notificationId) {
    return jdbc.queryForList(
        "select type || ' ' || coalesce(detail, 'null') from audit_event"
            + " where notification_id = ? order by occurred_at, id",
        String.class,
        notificationId);
  }

  private int count(String type) {
    return jdbc.queryForObject(
        "select count(*) from audit_event where type = ?", Integer.class, type);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
  }
}
