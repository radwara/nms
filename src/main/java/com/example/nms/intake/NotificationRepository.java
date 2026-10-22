package com.example.nms.intake;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** Ids of a submission's notifications, without loading content. Order is not defined. */
  @Query(
      "select new com.example.nms.intake.SubmittedNotification(n.recipientId, n.id, n.duplicateOf)"
          + " from Notification n where n.submissionId = :submissionId")
  List<SubmittedNotification> findIdsBySubmissionId(@Param("submissionId") UUID submissionId);

  /**
   * Routed originals with this event key and message, created after {@code since}, oldest first,
   * for every recipient: the caller picks its recipients (ADR-0006). Equality on every column of
   * {@code ix_notification_event_dedup} before the window range, so suppressed repeats and unrouted
   * rows are skipped by the index. Rows without a content hash never match.
   */
  @Query(
      """
      select new com.example.nms.intake.EventOriginal(n.recipientId, n.id)
        from Notification n
       where n.sourceSystem = :sourceSystem and n.eventId = :eventId and n.type = :type
         and n.contentHash = :contentHash and n.duplicateOf is null and n.routed = true
         and n.createdAt > :since
       order by n.createdAt, n.id
      """)
  List<EventOriginal> findEventOriginals(
      @Param("sourceSystem") String sourceSystem,
      @Param("eventId") String eventId,
      @Param("type") String type,
      @Param("contentHash") String contentHash,
      @Param("since") Instant since);

  /** Persists the routed flag of a submission's routed originals: one statement (ADR-0006). */
  @Modifying
  @Query("update Notification n set n.routed = true where n.id in :ids")
  int markRouted(@Param("ids") Collection<UUID> ids);

  /** Tenant-scoped lookup: another source system's notification is not found. */
  Optional<Notification> findByIdAndSourceSystem(UUID id, String sourceSystem);

  boolean existsByIdAndSourceSystem(UUID id, String sourceSystem);
}
