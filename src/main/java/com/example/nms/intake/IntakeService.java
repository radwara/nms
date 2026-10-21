package com.example.nms.intake;

import com.example.nms.audit.AuditRecorder;
import com.example.nms.directory.RecipientRepository;
import com.example.nms.intake.SubmitResult.Created;
import com.example.nms.intake.SubmitResult.InvalidTimes;
import com.example.nms.intake.SubmitResult.KeyConflict;
import com.example.nms.intake.SubmitResult.Replayed;
import com.example.nms.intake.SubmitResult.UnknownRecipients;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts a validated submission (ADR-0002): one transaction writes the idempotency record, one
 * notification per recipient, their routing, and {@code ACCEPTED} audits, or nothing.
 *
 * <p>Concurrent requests with one key: the idempotency record is written first, so the loser fails
 * on the unique key (new key) or updates no row (expired key). Its transaction rolls back and a new
 * one replays the winner's submission.
 */
@Service
public class IntakeService {

  private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

  private final IdempotencyRecordRepository records;
  private final NotificationRepository notifications;
  private final RecipientRepository recipients;
  private final RoutingStep routing;
  private final AuditRecorder audit;
  private final Clock clock;
  private final Duration retention;
  private final TransactionTemplate transaction;

  IntakeService(
      IdempotencyRecordRepository records,
      NotificationRepository notifications,
      RecipientRepository recipients,
      RoutingStep routing,
      AuditRecorder audit,
      Clock clock,
      IdempotencyProperties properties,
      PlatformTransactionManager transactionManager) {
    this.records = records;
    this.notifications = notifications;
    this.recipients = recipients;
    this.routing = routing;
    this.audit = audit;
    this.clock = clock;
    this.retention = properties.retention();
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public SubmitResult submit(SubmitCommand command) {
    try {
      return transaction.execute(status -> attempt(command));
    } catch (DataIntegrityViolationException | KeyClaimedConcurrently e) {
      // Outside the failed transaction: the winner has committed, so its record is visible.
      SubmitResult result = transaction.execute(status -> replayLiveRecord(command));
      if (result == null) {
        throw e; // not a lost race on the key
      }
      log.info("Concurrent submission with the same key resolved as {}", describe(result));
      return result;
    }
  }

  private SubmitResult attempt(SubmitCommand command) {
    Instant now = now();
    Optional<IdempotencyRecord> existing =
        records.findBySourceSystemAndIdempotencyKey(
            command.sourceSystem(), command.idempotencyKey());
    if (existing.isPresent() && existing.get().isLiveAt(now)) {
      return replayOrConflict(existing.get(), command, now);
    }
    // The rules below apply to new submissions only; a replay was answered above.
    List<TimeViolation> violations = TimeViolation.of(command, now);
    if (!violations.isEmpty()) {
      return new InvalidTimes(violations);
    }
    List<String> unknown = unknownRecipients(command);
    if (!unknown.isEmpty()) {
      return new UnknownRecipients(unknown);
    }

    UUID submissionId = UUID.randomUUID();
    claimKey(command, existing.isPresent(), submissionId, now);
    List<Notification> created =
        command.recipients().stream()
            .map(recipient -> notification(command, recipient, submissionId, now))
            .toList();
    notifications.saveAll(created);
    // Deliveries reference notifications by a plain column (no association Hibernate could order
    // inserts by), so the notifications go to the database before routing adds deliveries.
    notifications.flush();
    // ACCEPTED first, so each notification's history starts with it and routing events follow.
    created.forEach(n -> audit.accepted(command.sourceSystem(), n.getId(), now));
    routing.route(created, now);
    log.info(
        "Submission {} accepted: {} notifications for source system {}",
        submissionId,
        created.size(),
        command.sourceSystem());
    return new Created(view(created));
  }

  /** Writes the record first: a concurrent claim of the same key fails here, before any content. */
  private void claimKey(
      SubmitCommand command, boolean expiredRecordExists, UUID submissionId, Instant now) {
    Instant expiresAt = now.plus(retention);
    if (!expiredRecordExists) {
      records.saveAndFlush(
          new IdempotencyRecord(
              command.sourceSystem(),
              command.idempotencyKey(),
              command.requestHash(),
              submissionId,
              now,
              expiresAt));
    } else if (records.replaceExpired(
            command.sourceSystem(),
            command.idempotencyKey(),
            command.requestHash(),
            submissionId,
            now,
            expiresAt)
        != 1) {
      throw new KeyClaimedConcurrently();
    }
  }

  /** Replay or conflict against a live record, or {@code null} when there is none. */
  private SubmitResult replayLiveRecord(SubmitCommand command) {
    Instant now = now();
    return records
        .findBySourceSystemAndIdempotencyKey(command.sourceSystem(), command.idempotencyKey())
        .filter(record -> record.isLiveAt(now))
        .map(record -> replayOrConflict(record, command, now))
        .orElse(null);
  }

  private SubmitResult replayOrConflict(
      IdempotencyRecord record, SubmitCommand command, Instant now) {
    if (!record.getRequestHash().equals(command.requestHash())) {
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
    log.info(
        "Submission {} replayed for source system {}",
        record.getSubmissionId(),
        command.sourceSystem());
    return new Replayed(ordered);
  }

  private List<String> unknownRecipients(SubmitCommand command) {
    List<String> ids = command.recipients().stream().map(SubmitCommand.Recipient::userId).toList();
    Set<String> known = recipients.findExistingIds(ids);
    return ids.stream().filter(id -> !known.contains(id)).toList();
  }

  private static Notification notification(
      SubmitCommand command, SubmitCommand.Recipient recipient, UUID submissionId, Instant now) {
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
        now);
  }

  private static List<SubmittedNotification> view(List<Notification> notifications) {
    return notifications.stream()
        .map(n -> new SubmittedNotification(n.getRecipientId(), n.getId()))
        .toList();
  }

  private static String describe(SubmitResult result) {
    return result.getClass().getSimpleName();
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
