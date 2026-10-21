package com.example.nms.delivery;

import com.example.nms.common.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery queue (ADR-0004). Every worker write is a conditional update on {@code id}, the
 * expected {@code status}, and the expected {@code version}; it increments {@code version} and sets
 * {@code updated_at}. Zero rows updated means another writer won. Called outside a transaction,
 * each write commits on return.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

  /**
   * Due rows of one status, best first. One query per status (not {@code status in}) so the {@code
   * (status, priority, next_attempt_at)} index serves both the filter and the order.
   */
  @Query(
      """
      select d from Delivery d
      where d.status = :status and d.nextAttemptAt <= :now
      order by d.status, d.priority, d.nextAttemptAt
      """)
  List<Delivery> findDue(
      @Param("status") DeliveryStatus status, @Param("now") Instant now, Limit limit);

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
}
