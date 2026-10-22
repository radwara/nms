package com.example.nms.intake;

import com.example.nms.audit.AuditRecorder;
import com.example.nms.directory.RecipientRepository;
import com.example.nms.intake.SubmitResult.Created;
import com.example.nms.intake.SubmitResult.InProgress;
import com.example.nms.intake.SubmitResult.KeyConflict;
import com.example.nms.intake.SubmitResult.Replayed;
import com.example.nms.intake.SubmitResult.Unprocessable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts a validated submission (ADR-0002): one transaction writes the idempotency record, one
 * notification per recipient, their routing, and their audits, or nothing. A recipient whose event
 * and message repeat within the dedup window is suppressed (ADR-0006).
 *
 * <p>Concurrent requests with one key: the idempotency record is written first, so the loser fails
 * on the unique key (new key) or updates no row (expired key). Its transaction rolls back and a new
 * one replays the winner's submission.
 */
@Service
public class IntakeService {

  private static final Logger log = LoggerFactory.getLogger(IntakeService.class);
  private static final int LOCK_RETRIES = 3;
  private static final Duration LOCK_RETRY_BACKOFF = Duration.ofMillis(100);

  private final IdempotencyRecordRepository records;
  private final NotificationRepository notifications;
  private final RecipientRepository recipients;
  private final RoutingStep routing;
  private final EventDedup dedup;
  private final AuditRecorder audit;
  private final IntakeMetrics metrics;
  private final Clock clock;
  private final Duration retention;
  private final TransactionTemplate transaction;

  IntakeService(
      IdempotencyRecordRepository records,
      NotificationRepository notifications,
      RecipientRepository recipients,
      RoutingStep routing,
      EventDedup dedup,
      AuditRecorder audit,
      IntakeMetrics metrics,
      Clock clock,
      IdempotencyProperties properties,
      PlatformTransactionManager transactionManager) {
    this.records = records;
    this.notifications = notifications;
    this.recipients = recipients;
    this.routing = routing;
    this.dedup = dedup;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
    this.retention = properties.retention();
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * Runs its own transactions, so it must not be called inside one: a replay after a failed attempt
   * needs a fresh persistence context, and the submission must commit on its own.
   */
  public SubmitResult submit(SubmitCommand command) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("IntakeService.submit must not run inside a transaction");
    }
    String requestHash = RequestHash.of(command);
    try {
      return transaction.execute(status -> attempt(command, requestHash));
    } catch (DataIntegrityViolationException | KeyClaimedConcurrently e) {
      // Outside the failed transaction: the winner has committed, so its record is visible.
      SubmitResult result = transaction.execute(status -> replayLiveRecord(command, requestHash));
      if (result == null) {
        throw e; // not a lost race on the key
      }
      log.info("Concurrent submission with the same key resolved as {}", describe(result));
      return result;
    } catch (PessimisticLockingFailureException e) {
      // The lock wait timed out: the other request still holds the key. Its record becomes
      // visible when it commits, so look again briefly before giving up.
      return replayWhenCommitted(command, requestHash);
    }
  }

  private SubmitResult replayWhenCommitted(SubmitCommand command, String requestHash) {
    for (int attempt = 1; attempt <= LOCK_RETRIES; attempt++) {
      if (!pause(LOCK_RETRY_BACKOFF.multipliedBy(attempt))) {
        break;
      }
      SubmitResult result = transaction.execute(status -> replayLiveRecord(command, requestHash));
      if (result != null) {
        log.info("Locked key resolved as {} after {} retries", describe(result), attempt);
        return result;
      }
    }
    log.info(
        "Key still locked by a concurrent request for source system {}", command.sourceSystem());
    return new InProgress();
  }

  /** {@code false} if interrupted. */
  private static boolean pause(Duration duration) {
    try {
      Thread.sleep(duration);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private SubmitResult attempt(SubmitCommand command, String requestHash) {
    Instant now = now();
    Optional<IdempotencyRecord> existing =
        records.findBySourceSystemAndIdempotencyKey(
            command.sourceSystem(), command.idempotencyKey());
    if (existing.isPresent() && existing.get().isLiveAt(now)) {
      return replayOrConflict(existing.get(), command, requestHash, now);
    }
    // The rules below apply to new submissions only; a replay was answered above.
    List<TimeViolation> violations = TimeViolation.of(command, now);
    List<String> unknown = unknownRecipients(command);
    if (!violations.isEmpty() || !unknown.isEmpty()) {
      return new Unprocessable(violations, unknown);
    }
    return create(command, requestHash, existing.isPresent(), now);
  }

  /**
   * A new submission: claims the key, then writes one notification per recipient. Originals are
   * audited {@code ACCEPTED} and routed; event duplicates (ADR-0006) are written suppressed, with
   * no deliveries, and audited on both rows.
   */
  private SubmitResult create(
      SubmitCommand command, String requestHash, boolean expiredRecordExists, Instant now) {
    UUID submissionId = UUID.randomUUID();
    claimKey(command, requestHash, expiredRecordExists, submissionId, now);
    String contentHash = ContentHash.of(command);
    Map<String, UUID> duplicates = dedup.duplicates(command, contentHash, now);
    List<Notification> created =
        command.recipients().stream()
            .map(
                recipient ->
                    notification(
                        command,
                        recipient,
                        submissionId,
                        contentHash,
                        duplicates.get(recipient.userId()),
                        now))
            .toList();
    notifications.saveAll(created);
    // Deliveries reference notifications by a plain column (no association Hibernate could order
    // inserts by), so the notifications go to the database before routing adds deliveries.
    notifications.flush();
    List<Notification> originals = created.stream().filter(n -> !n.isSuppressed()).toList();
    // ACCEPTED first, so each notification's history starts with it and routing events follow.
    originals.forEach(n -> audit.accepted(command.sourceSystem(), n.getId(), now));
    created.stream()
        .filter(Notification::isSuppressed)
        .forEach(
            n -> {
              audit.suppressed(command.sourceSystem(), n.getId(), n.getDuplicateOf(), now);
              audit.duplicateEvent(command.sourceSystem(), n.getDuplicateOf(), n.getId(), now);
            });
    routing.route(originals, now);
    int suppressed = created.size() - originals.size();
    afterCommit(
        () -> {
          metrics.submitted(originals.size(), suppressed);
          log.info(
              "Submission {} accepted: {} notifications, {} suppressed, for source system {}",
              submissionId,
              originals.size(),
              suppressed,
              command.sourceSystem());
        });
    return new Created(view(created));
  }

  /** Writes the record first: a concurrent claim of the same key fails here, before any content. */
  private void claimKey(
      SubmitCommand command,
      String requestHash,
      boolean expiredRecordExists,
      UUID submissionId,
      Instant now) {
    Instant expiresAt = now.plus(retention);
    if (!expiredRecordExists) {
      records.saveAndFlush(
          new IdempotencyRecord(
              command.sourceSystem(),
              command.idempotencyKey(),
              requestHash,
              submissionId,
              now,
              expiresAt));
    } else if (records.replaceExpired(
            command.sourceSystem(),
            command.idempotencyKey(),
            requestHash,
            submissionId,
            now,
            expiresAt)
        != 1) {
      throw new KeyClaimedConcurrently();
    }
  }

  /** Replay or conflict against a live record, or {@code null} when there is none. */
  private SubmitResult replayLiveRecord(SubmitCommand command, String requestHash) {
    Instant now = now();
    return records
        .findBySourceSystemAndIdempotencyKey(command.sourceSystem(), command.idempotencyKey())
        .filter(record -> record.isLiveAt(now))
        .map(record -> replayOrConflict(record, command, requestHash, now))
        .orElse(null);
  }

  private SubmitResult replayOrConflict(
      IdempotencyRecord record, SubmitCommand command, String requestHash, Instant now) {
    if (!record.getRequestHash().equals(requestHash)) {
      return new KeyConflict();
    }
    Map<String, SubmittedNotification> byRecipient =
        notifications.findIdsBySubmissionId(record.getSubmissionId()).stream()
            .collect(Collectors.toMap(SubmittedNotification::recipientId, Function.identity()));
    // Same hash means same recipients in the same order, so the request gives the order back.
    List<SubmittedNotification> ordered =
        command.recipients().stream()
            .map(recipient -> byRecipient.get(recipient.userId()))
            .toList();
    if (ordered.contains(null)) {
      throw new IllegalStateException(
          "Submission " + record.getSubmissionId() + " is missing a recipient's notification");
    }
    // One event per notification: each notification's history and duplicate count show it.
    ordered.forEach(
        n -> audit.duplicateSubmission(command.sourceSystem(), n.notificationId(), now));
    afterCommit(
        () ->
            log.info(
                "Submission {} replayed for source system {}",
                record.getSubmissionId(),
                command.sourceSystem()));
    return new Replayed(ordered);
  }

  private List<String> unknownRecipients(SubmitCommand command) {
    List<String> ids = command.recipients().stream().map(SubmitCommand.Recipient::userId).toList();
    Set<String> known = recipients.findExistingIds(ids);
    return ids.stream().filter(id -> !known.contains(id)).toList();
  }

  private static Notification notification(
      SubmitCommand command,
      SubmitCommand.Recipient recipient,
      UUID submissionId,
      String contentHash,
      UUID duplicateOf,
      Instant now) {
    return new Notification(
        UUID.randomUUID(),
        submissionId,
        command.sourceSystem(),
        command.eventId(),
        command.type(),
        command.severity(),
        command.priority(),
        recipient.userId(),
        recipient.channels(),
        command.subject(),
        command.body(),
        command.scheduledAt(),
        command.expiresAt(),
        now,
        contentHash,
        duplicateOf);
  }

  private static List<SubmittedNotification> view(List<Notification> notifications) {
    return notifications.stream()
        .map(n -> new SubmittedNotification(n.getRecipientId(), n.getId(), n.getDuplicateOf()))
        .toList();
  }

  private static String describe(SubmitResult result) {
    return result.getClass().getSimpleName();
  }

  /** Logs outcomes only once they are durable. */
  private static void afterCommit(Runnable action) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  /** Database precision is microseconds; truncating keeps in-memory and stored values equal. */
  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /** The expired record this request tried to replace was renewed by a concurrent request. */
  private static final class KeyClaimedConcurrently extends RuntimeException {

    private static final long serialVersionUID = 1L;

    KeyClaimedConcurrently() {
      super("Idempotency key claimed by a concurrent request", null, false, false);
    }
  }
}
