package com.example.nms.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

  /**
   * A notification's history. Events written in one transaction share {@code occurred_at}; the id
   * (from a sequence) keeps their insert order.
   */
  List<AuditEvent> findByNotificationIdOrderByOccurredAtAscIdAsc(UUID notificationId);

  long countByNotificationIdAndType(UUID notificationId, AuditEventType type);
}
