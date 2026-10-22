package com.example.nms.intake;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** Ids of a submission's notifications, without loading content. Order is not defined. */
  @Query(
      "select new com.example.nms.intake.SubmittedNotification(n.recipientId, n.id, n.duplicateOf)"
          + " from Notification n where n.submissionId = :submissionId")
  List<SubmittedNotification> findIdsBySubmissionId(@Param("submissionId") UUID submissionId);

  /**
   * Originals (never suppressed rows) with this dedup key created after {@code since}, as {@code
   * (recipientId, id)}, oldest first. One query for all recipients of a submission (ADR-0006);
   * served by {@code ix_notification_event_dedup}. Rows without a content hash never match.
   */
  @Query(
      """
      select new com.example.nms.intake.SubmittedNotification(n.recipientId, n.id)
        from Notification n
       where n.sourceSystem = :sourceSystem and n.eventId = :eventId and n.type = :type
         and n.contentHash = :contentHash and n.recipientId in :recipientIds
         and n.createdAt > :since and n.duplicateOf is null
       order by n.createdAt, n.id
      """)
  List<SubmittedNotification> findEventOriginals(
      @Param("sourceSystem") String sourceSystem,
      @Param("eventId") String eventId,
      @Param("type") String type,
      @Param("contentHash") String contentHash,
      @Param("recipientIds") Collection<String> recipientIds,
      @Param("since") Instant since);

  /** Tenant-scoped lookup: another source system's notification is not found. */
  Optional<Notification> findByIdAndSourceSystem(UUID id, String sourceSystem);

  boolean existsByIdAndSourceSystem(UUID id, String sourceSystem);
}
