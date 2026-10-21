# Requirements

Source: assignment PDF "Notification Management Service". Each item has an ID used by issues, PRs, and tests.
The `Test` column is filled in as items are implemented; it becomes the traceability matrix.

## Scenarios

| ID | Requirement | Milestone | Test |
|---|---|---|---|
| SC-1 | Greenfield: submission, recipient + channel selection, async processing, delivery attempts, status retrieval | Greenfield | |
| SC-2 | Brownfield: enhancement/correction to the existing system that spans multiple layers | Brownfield | |
| SC-3 | Ambiguous requirement: show clarification, assumptions, and the resulting implementation | Ambiguous | |

## Functional

| ID | Requirement | Spec | Test |
|---|---|---|---|
| FR-1.1 | API accepts a notification request | 4.1 | `SubmitNotificationIT` |
| FR-1.2 | Request carries: notification id, source system, event/correlation id, type, severity, priority, 1..n recipients, requested/eligible channels, creation timestamp, optional schedule time, optional expiration time | 4.1 | `SubmitNotificationIT`, `SubmitValidationIT`, `SubmitRequestValidatorTest` |
| FR-2.1 | API returns overall notification status | 4.2 | |
| FR-2.2 | Status includes selected channels | 4.2 | |
| FR-2.3 | Status includes delivery status per recipient x channel | 4.2 | |
| FR-2.4 | Status includes relevant timestamps | 4.2 | |
| FR-2.5 | State model is documented and defensible | 4.2 | |
| FR-3.1 | Channels are determined from requested channels, severity, recipient preferences, and a routing policy | 4.3 | |
| FR-4.1 | Same idempotency key does not create a second logical notification | 4.4 | `SubmitNotificationIT`, `SubmitConcurrencyIT`, `SubmitLockTimeoutIT`, `SubmitReplayAfterExpiryIT`, `RequestHashTest` |
| FR-4.2 | Reprocessing a queued delivery does not cause uncontrolled duplicate side effects | 4.4 | |
| FR-4.3 | Duplicate/suppressed requests are visible in status or audit history | 4.4 | Submit side: `SubmitNotificationIT` (`DUPLICATE_SUBMISSION`) |
| FR-4.4 | Dedup boundary and retention policy are documented | 4.4 | `SubmitNotificationIT` (key expiry); ADR-0002 |
| FR-5.1 | Bounded retry for retryable delivery failures | 4.5 | |
| FR-5.2 | Failure classes are distinguished: transient, permanent rejection, invalid recipient, rate limit, timeout, auth error | 4.5 | |
| FR-6.1 | Audit records: accepted/rejected, routing decision, delivery queued/attempted/succeeded/failed, retry scheduled | 4.9 | Accepted: `SubmitNotificationIT`, `IntakeServiceIT`; rejected: `SubmitRejectionAuditIT`, `AuditRecorderIT` |
| FR-6.2 | Audit excludes unnecessary sensitive content and credentials | 4.9 | `SubmitRejectionAuditIT`, `SubmitValidationIT`, `AuditRecorderIT` |

## Non-functional (from sections 6-7)

| ID | Requirement | Test |
|---|---|---|
| NFR-1 | Modular, testable code with clear boundaries | |
| NFR-2 | Reliable: no lost notifications on restart, bounded retries | |
| NFR-3 | Secure: authenticated API, validated input, no secrets/PII in logs or audit | |
| NFR-4 | Scalable design path documented (multi-instance workers, external queue) | |
| NFR-5 | Observable: correlation ids in logs, health, delivery metrics | |
| NFR-6 | Safe change management: issue -> PR -> CI -> review for every change | |

## Deliverables

| ID | Deliverable |
|---|---|
| D-1 | Working prototype, runnable end-to-end |
| D-2 | Architecture overview: components, tools, execution approach, control flow, key decisions |
| D-3 | Three scenarios, each with decomposition, execution, validation |
| D-4 | Setup instructions |
| D-5 | Testing approach, limitations, trade-offs |
