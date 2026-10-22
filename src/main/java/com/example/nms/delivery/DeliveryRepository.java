package com.example.nms.delivery;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery queue (ADR-0004). Every worker write is a conditional update on {@code id}, the
 * expected {@code status}, and the expected {@code version}; a callback write is conditional on
 * {@code id}, {@code provider}, and {@code status in (SENDING, SENT, RETRY_SCHEDULED)}. Every write
 * increments {@code version} and sets {@code updated_at}. Zero rows updated means another writer
 * won. Called outside a transaction, each write commits on return.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

  /**
   * Due rows of one status and priority, earliest first, then oldest first (FIFO among rows a
   * rate-limit postpone moved to the same instant). Equality on both leading columns of the {@code
   * (status, priority, next_attempt_at)} index makes this a range scan that stops after {@code
   * limit} rows; one query per status and per priority, merged by the worker. Ordering by priority
   * inside one query would first walk every not-yet-due row of a better priority.
   */
  @Query(
      """
      select d from Delivery d
      where d.status = :status and d.priority = :priority and d.nextAttemptAt <= :now
      order by d.nextAttemptAt, d.createdAt
      """)
  List<Delivery> findDue(
      @Param("status") DeliveryStatus status,
      @Param("priority") Priority priority,
      @Param("now") Instant now,
      Limit limit);

  /**
   * The row, locked until the caller's transaction ends ({@code select ... for update}): a callback
   * reads the clock only after any concurrent worker write has committed.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from Delivery d where d.id = :id")
  Optional<Delivery> findForUpdate(@Param("id") UUID id);

  /** Whether another delivery of {@code provider} already has this vendor message id (unique). */
  boolean existsByProviderAndProviderMessageIdAndIdNot(
      String provider, String providerMessageId, UUID id);

  /** A notification's deliveries, one per channel. */
  List<Delivery> findByNotificationIdOrderByChannel(UUID notificationId);

  /** {@code SENDING} rows whose lease started before {@code claimedBefore} (crashed mid-send). */
  @Query(
      """
      select d from Delivery d
      where d.status = com.example.nms.common.DeliveryStatus.SENDING
        and d.nextAttemptAt <= :now and d.claimedAt < :claimedBefore
      order by d.status, d.priority, d.nextAttemptAt
      """)
  List<Delivery> findLeaseExpired(
      @Param("now") Instant now, @Param("claimedBefore") Instant claimedBefore, Limit limit);

  /** Claims a row for one send attempt: {@code SENDING}, lease from {@code now}, attempt + 1. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update Delivery d
      set d.status = com.example.nms.common.DeliveryStatus.SENDING, d.claimedAt = :now,
          d.attemptCount = d.attemptCount + 1, d.version = d.version + 1, d.updatedAt = :now
      where d.id = :id and d.status = :expected and d.version = :version
      """)
  int claim(
      @Param("id") UUID id,
      @Param("expected") DeliveryStatus expected,
      @Param("version") long version,
      @Param("now") Instant now);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update Delivery d
      set d.status = com.example.nms.common.DeliveryStatus.SENT,
          d.providerMessageId = :providerMessageId, d.sentAt = :now, d.nextAttemptAt = null,
          d.version = d.version + 1, d.updatedAt = :now
      where d.id = :id and d.status = com.example.nms.common.DeliveryStatus.SENDING
        and d.version = :version
      """)
  int markSent(
      @Param("id") UUID id,
      @Param("version") long version,
      @Param("providerMessageId") String providerMessageId,
      @Param("now") Instant now);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update Delivery d
      set d.status = com.example.nms.common.DeliveryStatus.RETRY_SCHEDULED,
          d.nextAttemptAt = :nextAttemptAt, d.version = d.version + 1, d.updatedAt = :now
      where d.id = :id and d.status = com.example.nms.common.DeliveryStatus.SENDING
        and d.version = :version
      """)
  int scheduleRetry(
      @Param("id") UUID id,
      @Param("version") long version,
      @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("now") Instant now);

  /** Final state ({@code FAILED} or {@code EXPIRED}) from {@code expected}; no longer due. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update Delivery d
      set d.status = :status, d.failureReason = :reason, d.finalizedAt = :now,
          d.nextAttemptAt = null, d.version = d.version + 1, d.updatedAt = :now
      where d.id = :id and d.status = :expected and d.version = :version
      """)
  int finish(
      @Param("id") UUID id,
      @Param("expected") DeliveryStatus expected,
      @Param("version") long version,
      @Param("status") DeliveryStatus status,
      @Param("reason") String reason,
      @Param("now") Instant now);

  /**
   * A provider callback's final state ({@code DELIVERED} or {@code FAILED}). Not conditional on the
   * version, so it also applies while the worker still holds the claim; the worker's {@code SENT}
   * write then changes nothing. On a {@code RETRY_SCHEDULED} row it clears {@code next_attempt_at}
   * and bumps the version, so the pending retry is never claimed. {@code sent_at} and {@code
   * provider_message_id} are kept if set.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update Delivery d
      set d.status = :status, d.failureReason = :reason, d.finalizedAt = :now,
          d.sentAt = coalesce(d.sentAt, :now),
          d.providerMessageId = coalesce(d.providerMessageId, :providerMessageId),
          d.nextAttemptAt = null, d.version = d.version + 1, d.updatedAt = :now
      where d.id = :id and d.provider = :provider
        and d.status in (com.example.nms.common.DeliveryStatus.SENDING,
                         com.example.nms.common.DeliveryStatus.SENT,
                         com.example.nms.common.DeliveryStatus.RETRY_SCHEDULED)
      """)
  int applyCallback(
      @Param("id") UUID id,
      @Param("provider") String provider,
      @Param("status") DeliveryStatus status,
      @Param("reason") String reason,
      @Param("providerMessageId") String providerMessageId,
      @Param("now") Instant now);

  /** Moves a row's due time without claiming it, e.g. after an unexpected error. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update Delivery d
      set d.nextAttemptAt = :nextAttemptAt, d.version = d.version + 1, d.updatedAt = :now
      where d.id = :id and d.status = :expected and d.version = :version
      """)
  int postpone(
      @Param("id") UUID id,
      @Param("expected") DeliveryStatus expected,
      @Param("version") long version,
      @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("now") Instant now);

  /**
   * Rate limited: moves every due row of one source system and channel to {@code until} in one
   * statement, with the conditions of a poll and of {@link #postpone}: {@code PENDING} or {@code
   * RETRY_SCHEDULED} due by {@code now}, or {@code SENDING} past the lease ({@code claimed_at <
   * claimedBefore}). Status and attempts unchanged; {@code version} bumped, so a claim or outcome
   * write with a version read before this changes nothing.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update Delivery d
      set d.nextAttemptAt = :until, d.version = d.version + 1, d.updatedAt = :now
      where d.channel = :channel and d.nextAttemptAt <= :now
        and d.notificationId in
            (select n.id from Notification n where n.sourceSystem = :sourceSystem)
        and (d.status in (com.example.nms.common.DeliveryStatus.PENDING,
                          com.example.nms.common.DeliveryStatus.RETRY_SCHEDULED)
             or (d.status = com.example.nms.common.DeliveryStatus.SENDING
                 and d.claimedAt < :claimedBefore))
      """)
  int postponeDue(
      @Param("sourceSystem") String sourceSystem,
      @Param("channel") Channel channel,
      @Param("now") Instant now,
      @Param("claimedBefore") Instant claimedBefore,
      @Param("until") Instant until);
}
