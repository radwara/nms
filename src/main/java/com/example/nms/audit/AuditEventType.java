package com.example.nms.audit;

/** Audit event type (architecture.md "Audit"). */
public enum AuditEventType {
  ACCEPTED,
  REJECTED,
  DUPLICATE_SUBMISSION,
  ROUTED,
  ROUTE_REMOVED,
  NOT_ROUTED,
  QUEUED,
  ATTEMPT_FAILED,
  RETRY_SCHEDULED,
  SENT,
  DELIVERED,
  FAILED,
  EXPIRED,
  CALLBACK_IGNORED
}
