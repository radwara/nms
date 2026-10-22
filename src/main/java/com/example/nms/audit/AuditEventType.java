package com.example.nms.audit;

/** Audit event type (architecture.md "Audit"). */
public enum AuditEventType {
  ACCEPTED,
  REJECTED,
  DUPLICATE_SUBMISSION,
  /** On a notification suppressed at intake as a repeat of an earlier event (ADR-0006). */
  SUPPRESSED,
  /** On the original: a later submission repeated its event and was suppressed. */
  DUPLICATE_EVENT,
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
