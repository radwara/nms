package com.example.nms.intake;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** Ids of a submission's notifications, without loading content. Order is not defined. */
  @Query(
      "select new com.example.nms.intake.SubmittedNotification(n.recipientId, n.id)"
          + " from Notification n where n.submissionId = :submissionId")
  List<SubmittedNotification> findIdsBySubmissionId(@Param("submissionId") UUID submissionId);
}
