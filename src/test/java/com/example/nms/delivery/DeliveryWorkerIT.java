package com.example.nms.delivery;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.TestEntities.NOW;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.intake.IntakeService;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import com.example.nms.intake.SubmitCommand;
import com.example.nms.intake.SubmitResult;
import com.example.nms.provider.ChannelProvider;
import com.example.nms.provider.ErrorCategory;
import com.example.nms.provider.SendRequest;
import com.example.nms.provider.SendResult;
import com.example.nms.provider.SendResult.Accepted;
import com.example.nms.provider.SendResult.Failed;
import com.example.nms.provider.SimulatedEmailProvider;
import com.example.nms.support.MutableClock;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * #10: the worker against the seeded directory and the simulated providers. Time is a {@link
 * MutableClock}; jitter always returns its bound, so backoff (3 attempts, base 500ms) is exactly
 * {@code 0.5s, 1s}. The poller is off; each test calls {@link DeliveryWorker#runOnce()}.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(DeliveryWorkerIT.TestBeans.class)
@ExtendWith(OutputCaptureExtension.class)
class DeliveryWorkerIT {

  private static final Pattern TRACE_ID = Pattern.compile("\"traceId\":\"([0-9a-f]{32})\"");

  @Autowired private DeliveryWorker worker;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private DeliveryOutcomes outcomes;
  @Autowired private NotificationRepository notifications;
  @Autowired private IntakeService intake;
  @Autowired private SimulatedEmailProvider email;
  @Autowired private ProbeProvider probe;
  @Autowired private MutableClock clock;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  // Claim order and due time

  @Test
  void highIsClaimedBeforeNormalBeforeLow() {
    Instant now = clock.instant();
    Delivery low = queue("user-ok", Channel.EMAIL, Priority.LOW, now.minusSeconds(3), null);
    Delivery normal = queue("user-ok", Channel.EMAIL, Priority.NORMAL, now.minusSeconds(2), null);
    Delivery high = queue("user-ok", Channel.EMAIL, Priority.HIGH, now.minusSeconds(1), null);

    worker.runOnce();

    assertThat(sentOrder()).containsExactly(high.getId(), normal.getId(), low.getId());
  }

  @Test
  void aFullBatchTakesHighPriorityFirstEvenWhenDueLater() {
    Instant now = clock.instant();
    for (int i = 0; i < 21; i++) {
      queue("user-ok", Channel.EMAIL, Priority.LOW, now.minusSeconds(60), null);
    }
    Delivery high = queue("user-ok", Channel.EMAIL, Priority.HIGH, now, null);

    assertThat(worker.runOnce()).isEqualTo(20);

    assertThat(sentOrder()).hasSize(20).first().isEqualTo(high.getId());
    assertThat(countByStatus(DeliveryStatus.PENDING)).isEqualTo(2);
  }

  @Test
  void futureScheduledAtIsNotSentBeforeIt() {
    Instant scheduledAt = clock.instant().plus(Duration.ofHours(1));
    UUID notificationId = submit("user-email-only", scheduledAt, null);
    long sendCalls = email.sendCalls();

    worker.runOnce();
    clock.advance(Duration.ofHours(1).minusNanos(1_000));
    worker.runOnce();
    assertThat(only(notificationId).getStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(email.sendCalls()).isEqualTo(sendCalls);

    clock.advance(Duration.ofNanos(1_000));
    worker.runOnce();
    assertThat(only(notificationId).getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  // Send outcomes per address (architecture.md "Simulated outcomes")

  static Stream<Arguments> sendOutcomes() {
    List<Arguments> cases = new ArrayList<>();
    for (Channel channel : Channel.values()) {
      cases.add(Arguments.of("user-ok", channel, DeliveryStatus.SENT, null, 1, List.of("SENT")));
      cases.add(retriedUntilExhausted("user-transient", channel, ErrorCategory.TRANSIENT, "503"));
      cases.add(retriedUntilExhausted("user-timeout", channel, ErrorCategory.TIMEOUT, "504"));
      cases.add(
          retriedUntilExhausted("user-ratelimit", channel, ErrorCategory.RATE_LIMITED, "429"));
      cases.add(failedOnce("user-reject", channel, ErrorCategory.PERMANENT_REJECTION, "422"));
      cases.add(failedOnce("user-invalid", channel, ErrorCategory.INVALID_RECIPIENT, "400"));
      cases.add(failedOnce("user-autherror", channel, ErrorCategory.AUTH_ERROR, "401"));
      cases.add(
          Arguments.of(
              "user-flaky2",
              channel,
              DeliveryStatus.SENT,
              null,
              3,
              List.of(
                  "ATTEMPT_FAILED attempt 1, TRANSIENT, code 503",
                  "RETRY_SCHEDULED in 0.5s",
                  "ATTEMPT_FAILED attempt 2, TRANSIENT, code 503",
                  "RETRY_SCHEDULED in 1s",
                  "SENT")));
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0} {1} -> {2} {3}")
  @MethodSource("sendOutcomes")
  void addressSelectsTheFinalStateAndAudits(
      String recipient,
      Channel channel,
      DeliveryStatus status,
      String reason,
      int attempts,
      List<String> history) {
    Delivery queued = queue(recipient, channel, Priority.NORMAL, clock.instant(), null);

    drain();

    Delivery done = reload(queued);
    assertThat(done.getStatus()).isEqualTo(status);
    assertThat(done.getFailureReason()).isEqualTo(reason);
    assertThat(done.getAttemptCount()).isEqualTo(attempts);
    assertThat(done.getNextAttemptAt()).isNull();
    assertThat(history(queued.getId())).containsExactlyElementsOf(history);
    if (status == DeliveryStatus.SENT) {
      assertThat(done.getProviderMessageId()).startsWith(channel == Channel.EMAIL ? "em-" : "sm-");
      assertThat(done.getSentAt()).isNotNull();
      assertThat(done.getFinalizedAt()).isNull();
    } else {
      assertThat(done.getFinalizedAt()).isNotNull();
    }
  }

  @Test
  void flaky2SubmittedThroughIntakeEndsSentAfterThreeAttempts() {
    UUID notificationId = submit("user-flaky2", null, null);

    drain();

    Delivery done = only(notificationId);
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(done.getAttemptCount()).isEqualTo(3);
    assertThat(
            jdbc.queryForList(
                "select type from audit_event where notification_id = ? order by occurred_at, id",
                String.class,
                notificationId))
        .containsExactly(
            "ACCEPTED",
            "ROUTED",
            "QUEUED",
            "ATTEMPT_FAILED",
            "RETRY_SCHEDULED",
            "ATTEMPT_FAILED",
            "RETRY_SCHEDULED",
            "SENT");
  }

  // Retry timing

  @Test
  void transientFailureIsRetriedAfterTheBackoffNotBefore() {
    Instant start = clock.instant();
    Delivery queued = queue("user-transient", Channel.EMAIL, Priority.NORMAL, start, null);

    worker.runOnce();
    assertThat(reload(queued).getNextAttemptAt()).isEqualTo(start.plusMillis(500));
    assertThat(reload(queued).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);

    clock.advance(Duration.ofMillis(500).minusNanos(1_000));
    worker.runOnce();
    assertThat(reload(queued).getAttemptCount()).isOne();

    clock.advance(Duration.ofNanos(1_000));
    worker.runOnce();
    assertThat(reload(queued).getAttemptCount()).isEqualTo(2);
    assertThat(reload(queued).getNextAttemptAt()).isEqualTo(start.plusMillis(500 + 1_000));
  }

  @Test
  void attemptsExhaustedFailsWithRetriesExhausted() {
    Delivery queued = queue("user-timeout", Channel.SMS, Priority.NORMAL, clock.instant(), null);

    drain();

    Delivery done = reload(queued);
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(done.getFailureReason()).isEqualTo("RETRIES_EXHAUSTED");
    assertThat(done.getAttemptCount()).isEqualTo(3);
    assertThat(history(queued.getId())).last().isEqualTo("FAILED RETRIES_EXHAUSTED");
  }

  @Test
  void rateLimitedHonoursRetryAfter() {
    Instant start = clock.instant();
    Delivery queued = queue("user-ratelimit", Channel.EMAIL, Priority.NORMAL, start, null);

    worker.runOnce();
    assertThat(reload(queued).getNextAttemptAt()).isEqualTo(start.plusSeconds(5));
    assertThat(history(queued.getId()))
        .containsExactly(
            "ATTEMPT_FAILED attempt 1, RATE_LIMITED, code 429", "RETRY_SCHEDULED in 5s");

    clock.advance(Duration.ofSeconds(5).minusNanos(1_000));
    worker.runOnce();
    assertThat(reload(queued).getAttemptCount()).isOne();

    clock.advance(Duration.ofNanos(1_000));
    worker.runOnce();
    assertThat(reload(queued).getAttemptCount()).isEqualTo(2);
  }

  // Expiry

  @Test
  void pendingDeliveryPastExpiresAtExpiresWithoutASend() {
    Instant start = clock.instant();
    Delivery queued =
        queue("user-ok", Channel.EMAIL, Priority.NORMAL, start, start.plusSeconds(60));
    long sendCalls = email.sendCalls();
    clock.advance(Duration.ofSeconds(60));

    worker.runOnce();

    Delivery done = reload(queued);
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.EXPIRED);
    assertThat(done.getAttemptCount()).isZero();
    assertThat(done.getFinalizedAt()).isEqualTo(start.plusSeconds(60));
    assertThat(done.getNextAttemptAt()).isNull();
    assertThat(email.sendCalls()).isEqualTo(sendCalls);
    assertThat(history(queued.getId())).containsExactly("EXPIRED before send");
  }

  @Test
  void retryScheduledDeliveryPastExpiresAtExpiresWithoutASend() {
    Instant start = clock.instant();
    Delivery queued =
        queue("user-transient", Channel.EMAIL, Priority.NORMAL, start, start.plusSeconds(60));
    worker.runOnce();
    long sendCalls = email.sendCalls();
    clock.advance(Duration.ofSeconds(60));

    worker.runOnce();

    assertThat(reload(queued).getStatus()).isEqualTo(DeliveryStatus.EXPIRED);
    assertThat(email.sendCalls()).isEqualTo(sendCalls);
    assertThat(history(queued.getId()))
        .containsExactly(
            "ATTEMPT_FAILED attempt 1, TRANSIENT, code 503",
            "RETRY_SCHEDULED in 0.5s",
            "EXPIRED before send");
  }

  @Test
  void nextAttemptPastExpiresAtExpires() {
    Instant start = clock.instant();
    Delivery queued =
        queue("user-transient", Channel.SMS, Priority.NORMAL, start, start.plusMillis(500));

    worker.runOnce();

    assertThat(reload(queued).getStatus()).isEqualTo(DeliveryStatus.EXPIRED);
    assertThat(reload(queued).getFailureReason()).isNull();
    assertThat(history(queued.getId()))
        .containsExactly(
            "ATTEMPT_FAILED attempt 1, TRANSIENT, code 503",
            "EXPIRED next attempt after expiresAt");
  }

  // Claim, lease, and restart safety (NFR-2)

  @Test
  void claimIsCommittedBeforeTheSendAndTheSendRunsOutsideATransaction() {
    Notification notification =
        notifications.save(notification("user-ok", Priority.NORMAL, clock.instant(), null));
    Delivery queued =
        deliveries.save(
            new Delivery(
                UUID.randomUUID(),
                notification.getId(),
                Channel.EMAIL,
                ProbeProvider.NAME,
                Priority.NORMAL,
                RoutingReason.REQUESTED,
                clock.instant(),
                clock.instant()));

    worker.runOnce();

    assertThat(probe.seen).hasSize(1);
    assertThat(probe.seen.getFirst())
        .containsEntry("status", "SENDING")
        .containsEntry("version", 1L)
        .containsEntry("inTransaction", false);
    assertThat(reload(queued).getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  @Test
  void expiredLeaseIsReclaimedAndResentWithTheSameDeliveryId() {
    Instant start = clock.instant();
    Delivery queued = queue("user-ok", Channel.EMAIL, Priority.NORMAL, start, null);
    Notification notification = notifications.findById(queued.getNotificationId()).orElseThrow();
    // A worker claims, the vendor accepts, and the worker dies before writing SENT.
    assertThat(deliveries.claim(queued.getId(), DeliveryStatus.PENDING, 0, start)).isOne();
    Claim crashed = Claim.of(queued, notification);
    String messageId =
        ((Accepted)
                email.send(
                    new SendRequest(queued.getId(), "ok.delivered@sim.test", "subject", "body")))
            .providerMessageId();
    int acceptedMessages = email.acceptedMessages();

    clock.advance(Duration.ofSeconds(30));
    worker.runOnce();
    assertThat(reload(queued).getStatus()).isEqualTo(DeliveryStatus.SENDING);

    clock.advance(Duration.ofNanos(1_000));
    worker.runOnce();

    Delivery done = reload(queued);
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(done.getProviderMessageId()).isEqualTo(messageId);
    assertThat(done.getAttemptCount()).isEqualTo(2);
    assertThat(email.acceptedMessages()).isEqualTo(acceptedMessages);

    // The crashed worker comes back and writes its outcome: nothing changes.
    long version = done.getVersion();
    Instant late = clock.instant();
    Failed timeout = new Failed(ErrorCategory.TIMEOUT, "504");
    assertThat(outcomes.sent(crashed, "em-other", late)).isFalse();
    assertThat(outcomes.failed(crashed, timeout, "TIMEOUT", late)).isFalse();
    assertThat(outcomes.retry(crashed, timeout, late, Duration.ZERO, late)).isFalse();
    assertThat(outcomes.expiredAfterAttempt(crashed, timeout, late)).isFalse();
    Delivery after = reload(queued);
    assertThat(after.getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(after.getVersion()).isEqualTo(version);
    assertThat(after.getProviderMessageId()).isEqualTo(messageId);
    assertThat(history(queued.getId())).containsExactly("SENT");
  }

  // Rows that cannot be sent, and providers that misbehave

  @Test
  void poisonRowsFailWithoutAClaimAndDoNotStallTheQueue() {
    Instant now = clock.instant();
    List<Delivery> noAdapter = new ArrayList<>();
    List<Delivery> noAddress = new ArrayList<>();
    List<Delivery> noRecipient = new ArrayList<>();
    List<Delivery> noNotification = new ArrayList<>();
    // The schema's foreign keys rule out the last two; switch them off to create the rows.
    jdbc.execute("set referential_integrity false");
    try {
      for (int i = 0; i < 5; i++) {
        Notification ok = notifications.save(notification("user-ok", Priority.HIGH, now, null));
        noAdapter.add(deliveries.save(onProvider(ok, "gone-email", now)));
        Notification emailOnly =
            notifications.save(notification("user-email-only", Priority.HIGH, now, null));
        noAddress.add(deliveries.save(delivery(emailOnly, Channel.SMS, now)));
        Notification ghost =
            notifications.save(notification("no-such-user", Priority.HIGH, now, null));
        noRecipient.add(deliveries.save(delivery(ghost, Channel.EMAIL, now)));
        Notification unsaved = notification("user-ok", Priority.HIGH, now, null);
        noNotification.add(deliveries.save(delivery(unsaved, Channel.EMAIL, now)));
      }
    } finally {
      jdbc.execute("set referential_integrity true");
    }
    Delivery valid = queue("user-ok", Channel.EMAIL, Priority.NORMAL, now, null);

    worker.runOnce();
    worker.runOnce();

    assertThat(reload(valid).getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertFailedBeforeSend(noAdapter, "NO_PROVIDER_ADAPTER", true);
    assertFailedBeforeSend(noAddress, "NO_ADDRESS", true);
    assertFailedBeforeSend(noRecipient, "MISSING_RECIPIENT", true);
    assertFailedBeforeSend(noNotification, "MISSING_NOTIFICATION", false);
  }

  @Test
  void throwingProviderIsRetriedAsTransientUntilExhausted() {
    Notification notification =
        notifications.save(notification("user-ok", Priority.NORMAL, clock.instant(), null));
    Delivery queued =
        deliveries.save(onProvider(notification, ThrowingProvider.NAME, clock.instant()));

    drain();

    Delivery done = reload(queued);
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(done.getFailureReason()).isEqualTo("RETRIES_EXHAUSTED");
    assertThat(done.getAttemptCount()).isEqualTo(3);
    assertThat(history(queued.getId()))
        .filteredOn(line -> line.startsWith("ATTEMPT_FAILED"))
        .containsExactly(
            "ATTEMPT_FAILED attempt 1, TRANSIENT, code exception",
            "ATTEMPT_FAILED attempt 2, TRANSIENT, code exception",
            "ATTEMPT_FAILED attempt 3, TRANSIENT, code exception");
  }

  @Test
  void reclaimedRowWithNoAttemptsLeftFailsWithoutASend() {
    Instant start = clock.instant();
    Delivery queued = queue("user-ok", Channel.EMAIL, Priority.NORMAL, start, null);
    // Every attempt (3) started; the last worker died mid-send.
    jdbc.update(
        "update delivery set status = 'SENDING', attempt_count = 3, claimed_at = ? where id = ?",
        Timestamp.from(start),
        queued.getId());
    long sendCalls = email.sendCalls();
    clock.advance(Duration.ofSeconds(31));

    worker.runOnce();

    Delivery done = reload(queued);
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(done.getFailureReason()).isEqualTo("RETRIES_EXHAUSTED");
    assertThat(done.getAttemptCount()).isEqualTo(3);
    assertThat(email.sendCalls()).isEqualTo(sendCalls);
    assertThat(history(queued.getId())).containsExactly("FAILED RETRIES_EXHAUSTED");
  }

  @Test
  void reclaimedRowPastExpiresAtExpiresAndSaysItMayHaveBeenSent() {
    Instant start = clock.instant();
    Delivery queued =
        queue("user-ok", Channel.EMAIL, Priority.NORMAL, start, start.plusSeconds(10));
    deliveries.claim(queued.getId(), DeliveryStatus.PENDING, 0, start);
    clock.advance(Duration.ofSeconds(31));

    worker.runOnce();

    assertThat(reload(queued).getStatus()).isEqualTo(DeliveryStatus.EXPIRED);
    assertThat(history(queued.getId())).containsExactly("EXPIRED lease expired after expiresAt");
  }

  // Batch loading

  @Test
  void notificationsAndRecipientsAreLoadedOncePerBatch() {
    Instant now = clock.instant();
    for (String recipient : List.of("user-ok", "user-ok", "user-email-only", "user-silent")) {
      queue(recipient, Channel.EMAIL, Priority.NORMAL, now, null);
    }
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    assertThat(worker.runOnce()).isEqualTo(4);

    assertThat(statistics.getEntityStatistics(Notification.class.getName()).getLoadCount())
        .isEqualTo(4);
    assertThat(statistics.getEntityFetchCount()).isZero();
    assertThat(executions(statistics, "from notification ")).isOne();
    assertThat(executions(statistics, "from recipient ")).isOne();
  }

  /** Executions of the one query whose text contains {@code fragment}. */
  private static long executions(Statistics statistics, String fragment) {
    List<String> matching =
        Arrays.stream(statistics.getQueries()).filter(q -> q.contains(fragment)).toList();
    assertThat(matching).hasSize(1);
    return statistics.getQueryStatistics(matching.getFirst()).getExecutionCount();
  }

  // Logs

  @Test
  void attemptLogsCarryAFreshTraceIdAndNoAddress(CapturedOutput output) {
    Instant now = clock.instant();
    Delivery first = queue("user-ok", Channel.EMAIL, Priority.NORMAL, now, null);
    Delivery second = queue("user-ok", Channel.SMS, Priority.NORMAL, now, null);

    worker.runOnce();

    String firstLine = logLine(output, first.getId());
    String secondLine = logLine(output, second.getId());
    assertThat(traceId(firstLine)).isNotEqualTo(traceId(secondLine));
    assertThat(output.getOut()).doesNotContain("ok.delivered@sim.test", "+15550100000");
  }

  @Test
  void authErrorIsLoggedAtErrorLevel(CapturedOutput output) {
    Delivery queued =
        queue("user-autherror", Channel.EMAIL, Priority.NORMAL, clock.instant(), null);

    worker.runOnce();

    assertThat(output.getOut().lines())
        .anySatisfy(
            line ->
                assertThat(line)
                    .contains("\"level\":\"ERROR\"", "rejected our credentials", "sim-email")
                    .contains(queued.getId().toString())
                    .doesNotContain("autherror.delivered@sim.test"));
  }

  // Helpers

  /** Runs the worker until nothing is due, moving time forward an hour between polls. */
  private void drain() {
    for (int i = 0; i < 10; i++) {
      worker.runOnce();
      clock.advance(Duration.ofHours(1));
    }
  }

  private Delivery queue(
      String recipient, Channel channel, Priority priority, Instant dueAt, Instant expiresAt) {
    Notification notification =
        notifications.save(notification(recipient, priority, clock.instant(), expiresAt));
    return deliveries.save(delivery(notification, channel, dueAt));
  }

  private UUID submit(String recipient, Instant scheduledAt, Instant expiresAt) {
    SubmitResult result =
        intake.submit(
            new SubmitCommand(
                "billing",
                "key-" + UUID.randomUUID(),
                "evt-1",
                "PAYMENT_DUE",
                Severity.INFO,
                Priority.NORMAL,
                List.of(new SubmitCommand.Recipient(recipient, List.of(Channel.EMAIL))),
                "subject",
                "body",
                scheduledAt,
                expiresAt));
    return ((SubmitResult.Created) result).notifications().getFirst().notificationId();
  }

  private void assertFailedBeforeSend(List<Delivery> rows, String reason, boolean audited) {
    for (Delivery row : rows) {
      Delivery done = reload(row);
      assertThat(done.getStatus()).as(reason).isEqualTo(DeliveryStatus.FAILED);
      assertThat(done.getFailureReason()).isEqualTo(reason);
      assertThat(done.getAttemptCount()).as("not claimed").isZero();
      assertThat(history(row.getId()))
          .containsExactlyElementsOf(audited ? List.of("FAILED " + reason) : List.of());
    }
  }

  private static Delivery onProvider(Notification notification, String provider, Instant dueAt) {
    return new Delivery(
        UUID.randomUUID(),
        notification.getId(),
        Channel.EMAIL,
        provider,
        notification.getPriority(),
        RoutingReason.REQUESTED,
        dueAt,
        notification.getCreatedAt());
  }

  private Delivery reload(Delivery delivery) {
    return deliveries.findById(delivery.getId()).orElseThrow();
  }

  private Delivery only(UUID notificationId) {
    List<Delivery> found =
        deliveries.findAll().stream()
            .filter(d -> d.getNotificationId().equals(notificationId))
            .toList();
    assertThat(found).hasSize(1);
    return found.getFirst();
  }

  private long countByStatus(DeliveryStatus status) {
    return jdbc.queryForObject(
        "select count(*) from delivery where status = ?", Long.class, status.name());
  }

  private List<UUID> sentOrder() {
    return jdbc.queryForList(
        "select delivery_id from audit_event where type = 'SENT' order by id", UUID.class);
  }

  private List<String> history(UUID deliveryId) {
    return jdbc.query(
        "select type, detail from audit_event where delivery_id = ? order by occurred_at, id",
        (rs, i) ->
            rs.getString("type")
                + (rs.getString("detail") == null ? "" : " " + rs.getString("detail")),
        deliveryId);
  }

  private static String logLine(CapturedOutput output, UUID deliveryId) {
    return output
        .getOut()
        .lines()
        .filter(line -> line.contains("Delivery " + deliveryId + " attempt 1"))
        .findFirst()
        .orElseThrow();
  }

  private static String traceId(String line) {
    Matcher matcher = TRACE_ID.matcher(line);
    assertThat(matcher.find()).as("traceId in %s", line).isTrue();
    return matcher.group(1);
  }

  private static Stream<String> retryLines(ErrorCategory category, String code) {
    List<String> lines = new ArrayList<>();
    for (int attempt = 1; attempt <= 3; attempt++) {
      lines.add("ATTEMPT_FAILED attempt " + attempt + ", " + category + ", code " + code);
      if (attempt < 3) {
        String delay = category == ErrorCategory.RATE_LIMITED ? "5s" : attempt == 1 ? "0.5s" : "1s";
        lines.add("RETRY_SCHEDULED in " + delay);
      }
    }
    lines.add("FAILED RETRIES_EXHAUSTED");
    return lines.stream();
  }

  private static Arguments retriedUntilExhausted(
      String recipient, Channel channel, ErrorCategory category, String code) {
    return Arguments.of(
        recipient,
        channel,
        DeliveryStatus.FAILED,
        "RETRIES_EXHAUSTED",
        3,
        retryLines(category, code).toList());
  }

  private static Arguments failedOnce(
      String recipient, Channel channel, ErrorCategory category, String code) {
    return Arguments.of(
        recipient,
        channel,
        DeliveryStatus.FAILED,
        category.name(),
        1,
        List.of("ATTEMPT_FAILED attempt 1, " + category + ", code " + code, "FAILED " + category));
  }

  /** Records what another connection sees of the delivery while the send is in progress. */
  static class ProbeProvider implements ChannelProvider {

    static final String NAME = "probe-email";

    private final JdbcTemplate jdbc;
    final List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();

    ProbeProvider(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public String name() {
      return NAME;
    }

    @Override
    public Channel channel() {
      return Channel.EMAIL;
    }

    @Override
    public SendResult send(SendRequest request) {
      boolean inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
      Map<String, Object> row =
          new HashMap<>(
              jdbc.queryForMap(
                  "select status, version from delivery where id = ?", request.deliveryId()));
      row.put("inTransaction", inTransaction);
      seen.add(row);
      return new Accepted("probe-" + request.deliveryId());
    }
  }

  /** An adapter that breaks its contract by throwing. */
  static class ThrowingProvider implements ChannelProvider {

    static final String NAME = "throwing-email";

    @Override
    public String name() {
      return NAME;
    }

    @Override
    public Channel channel() {
      return Channel.EMAIL;
    }

    @Override
    public SendResult send(SendRequest request) {
      throw new IllegalStateException("adapter bug");
    }
  }

  @TestConfiguration
  static class TestBeans {

    @Bean
    ThrowingProvider throwingProvider() {
      return new ThrowingProvider();
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(NOW);
    }

    /** The longest delay: backoff is exactly the bound. */
    @Bean
    @Primary
    Jitter longestJitter() {
      return max -> max;
    }

    @Bean
    ProbeProvider probeProvider(JdbcTemplate jdbc) {
      return new ProbeProvider(jdbc);
    }
  }
}
