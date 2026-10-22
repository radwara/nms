# Requirements

Source: assignment PDF "Notification Management Service". Each item has an ID used by issues, PRs, and tests.
The `Test` column is filled in as items are implemented; it becomes the traceability matrix.

## Scenarios

| ID | Requirement | Milestone | Test |
|---|---|---|---|
| SC-1 | Greenfield: submission, recipient + channel selection, async processing, delivery attempts, status retrieval | Greenfield | `SubmitNotificationIT`, `RoutingIT`, `DeliveryWorkerIT`, `NotificationStatusIT`, `SimulatedCallbacksIT` (submit -> send -> callback -> status on a real server), `GreenfieldE2EIT` (every flow over HTTP with the background worker), `scripts/demo.sh` (CI smoke run) |
| SC-2 | Brownfield: enhancement/correction to the existing system that spans multiple layers | Brownfield | Event-level dedup (#14, `docs/scenarios/brownfield.md`, ADR-0006): schema `SchemaMigrationIT` (V3); intake `EventDedupIT`, `EventDedupDisabledIT`, `EventDedupLookupIT` (index plan on storm data), `ContentHashTest`, `DedupPropertiesTest`, `NotificationTest`, `SubmitStatementsIT` (one lookup); API `SubmitDedupIT`, `SubmitResponseTest`; status `OverallStatusTest`; metrics `IntakeMetricsIT`; race `EventDedupRaceIT`; `scripts/demo.sh` step 6. Behaviour-preserving refactor first, zero test changes. Outbound rate limit (#46, `docs/scenarios/brownfield-2.md`, ADR-0007): `FixedWindowRateLimiterTest` (incl. concurrent callers), `RateLimitPropertiesTest`, `RateLimitIT`, `RateLimitDefaultsIT`, `RateLimitFloodIT` (fairness under a flood), `DeliveryWorkerRateLimitTest`. Refactor first, zero test changes. Kafka fast path for EMAIL (#47, `docs/scenarios/brownfield-2.md`, ADR-0008): `KafkaEmailDispatchIT` (embedded broker: intake -> Kafka -> `SENT` inside the grace, same id twice sends once, SMS via the poller), `KafkaPublishFailureIT` (poller sends after the grace), `KafkaBrokerDownSubmitIT` (submit does not wait for Kafka), `KafkaDisabledIT`, `DeliveryGraceQueryIT`, `DeliveryWorkerFastPathTest`, `EmailDispatchPublisherTest`; poll index `DeliveryQueueIT` (#51). Refactor first, zero test changes |
| SC-3 | Ambiguous requirement: show clarification, assumptions, and the resulting implementation | Ambiguous | `docs/scenarios/ambiguous.md`, A-30; `ChannelSelectionTest`, `RoutingIT`, `RoutingPolicyIT`, `SubmitNotificationIT` (CRITICAL override, INFO / WARNING opt-out); `scripts/demo.sh` step 3 |

## Functional

| ID | Requirement | Spec | Test |
|---|---|---|---|
| FR-1.1 | API accepts a notification request | 4.1 | `SubmitNotificationIT`, `GreenfieldE2EIT` |
| FR-1.2 | Request carries: notification id, source system, event/correlation id, type, severity, priority, 1..n recipients, requested/eligible channels, creation timestamp, optional schedule time, optional expiration time | 4.1 | `SubmitNotificationIT`, `SubmitValidationIT`, `SubmitRequestValidatorTest` |
| FR-2.1 | API returns overall notification status | 4.2 | `OverallStatusTest` (every table row), `NotificationStatusIT`, `SimulatedCallbacksIT`, `GreenfieldE2EIT` |
| FR-2.2 | Status includes selected channels | 4.2 | `NotificationStatusIT` (deliveries per channel, `requestedChannels`, `NOT_ROUTED`) |
| FR-2.3 | Status includes delivery status per recipient x channel | 4.2 | `NotificationStatusIT` (status, attempts, reason), `ProviderCallbackIT`, `DeliveryCallbacksIT` |
| FR-2.4 | Status includes relevant timestamps | 4.2 | `NotificationStatusIT` (`createdAt`, `scheduledAt`, `expiresAt`, `sentAt`, `finalizedAt`) |
| FR-2.5 | State model is documented and defensible | 4.2 | architecture.md (Delivery lifecycle, Overall status), ADR-0003; `OverallStatusTest`, `DeliveryCallbacksIT` |
| FR-3.1 | Channels are determined from requested channels, severity, recipient preferences, and a routing policy | 4.3 | `ChannelSelectionTest`, `RoutingIT`, `RoutingPolicyIT`, `RoutingPropertiesTest`, `RoutingRollbackIT`, `SubmitStatementsIT` (batching) |
| FR-4.1 | Same idempotency key does not create a second logical notification | 4.4 | `SubmitNotificationIT`, `SubmitConcurrencyIT`, `SubmitLockTimeoutIT`, `SubmitReplayAfterExpiryIT`, `RequestHashTest`, `GreenfieldE2EIT` (replay over HTTP) |
| FR-4.2 | Reprocessing a queued delivery does not cause uncontrolled duplicate side effects | 4.4 | `DeliveryQueueIT` (one claimer wins, stale writes change nothing), `DeliveryWorkerIT` (lease reclaim resends with the same `deliveryId`), `SimulatedProviderTest` (dedupe), `DeliveryCallbacksIT` (a callback during `SENDING` wins; the stale `SENT` write changes nothing; a callback for `RETRY_SCHEDULED` cancels the retry, no second send; repeats change nothing), `SimulatedCallbacksIT` (a deduped resend schedules no second callback) |
| FR-4.3 | Duplicate/suppressed requests are visible in status or audit history | 4.4 | Submit side: `SubmitNotificationIT` (`DUPLICATE_SUBMISSION`); status side: `NotificationStatusIT` (`duplicateSubmissions`, audit API), `GreenfieldE2EIT`; callbacks: `DeliveryCallbacksIT` (`CALLBACK_IGNORED`); event dedup: `EventDedupIT` (`SUPPRESSED` and `DUPLICATE_EVENT` audits), `SubmitDedupIT` (per-item `SUPPRESSED` + `duplicateOf`, `overallStatus` `SUPPRESSED`, audit API), `scripts/demo.sh` step 6 |
| FR-4.4 | Dedup boundary and retention policy are documented | 4.4 | `SubmitNotificationIT` (key expiry); ADR-0002; event dedup boundary and window: ADR-0006, A-35, A-36, A-39, `EventDedupIT` (window strict and anchored on a routed original, `NOT_ROUTED` does not anchor, `FAILED` / `EXPIRED` do, key fields, precedence, pre-V3 rows), `DedupPropertiesTest` |
| FR-5.1 | Bounded retry for retryable delivery failures | 4.5 | `RetryPolicyTest`, `DeliveryWorkerIT`, `GreenfieldE2EIT` (`flaky2`: 3 attempts, then delivered) |
| FR-5.2 | Failure classes are distinguished: transient, permanent rejection, invalid recipient, rate limit, timeout, auth error | 4.5 | `SimulatedProviderTest`, `RetryPolicyTest`, `DeliveryWorkerIT`; outbound rate limit (#46, ADR-0007): `RateLimitIT`, `RateLimitFloodIT` |
| FR-6.1 | Audit records: accepted/rejected, routing decision, delivery queued/attempted/succeeded/failed, retry scheduled | 4.9 | Accepted: `SubmitNotificationIT`, `IntakeServiceIT`; rejected: `SubmitRejectionAuditIT`, `AuditRecorderIT`; routing decision and queued: `RoutingIT`; attempted, succeeded, failed, retry scheduled: `DeliveryWorkerIT`; delivered/failed by callback: `DeliveryCallbacksIT`; audit API order and codes only: `NotificationStatusIT` |
| FR-6.2 | Audit excludes unnecessary sensitive content and credentials | 4.9 | `SubmitRejectionAuditIT`, `AuditRecorderIT`, `NotificationStatusIT` (audit API: codes only), `ProviderCallbackIT` (`reasonCode` limited to code characters, values not echoed), `GreenfieldE2EIT` (every audit row and all captured logs of the run: no subject, body, email, phone, API key, or Idempotency-Key), `EventDedupIT` (no content or content hash in logs or audit) |

## Non-functional (from sections 6-7)

| ID | Requirement | Test |
|---|---|---|
| NFR-1 | Modular, testable code with clear boundaries | `PackageDependencyTest` (feature packages never import `api` or `security` internals, ADR-0005); unit tests per module without a Spring context (`*Test`) |
| NFR-2 | Reliable: no lost notifications on restart, bounded retries | `DeliveryWorkerIT` (simulated crash mid-send (lease reclaim), exhaustion), `DeliveryQueueIT` (claim committed before send), `RetryPolicyTest`, `GreenfieldE2EIT` (retry path on the real poller) |
| NFR-3 | Secure: authenticated API, validated input, no secrets/PII in logs or audit | Auth: `ApiKeyAuthenticationIT`, `ApiKeyAuthenticatorTest`, `WebhookTokenAuthenticationIT`, `NotificationStatusIT` (other tenant's id is 404); input: `SubmitValidationIT`, `ProviderCallbackIT`, `ErrorContractIT`, `ErrorContractTomcatIT`; no secrets/PII: `GreenfieldE2EIT` (leak check), `ApiKeyAuthenticationIT` and `WebhookTokenAuthenticationIT` (no keys or hashes in logs), `SubmitConcurrencyIT` (no Idempotency-Key in logs), `SubmitValidationIT` (submitted values not logged or echoed), `SubmitRejectionAuditIT`; CI secret scan |
| NFR-4 | Scalable design path documented (multi-instance workers, external queue) | Not tested: design only (architecture.md "Scaling path", ADR-0004); the multi-instance path is not built. `DeliveryQueueIT` (concurrent claimers, one wins) covers the claim protocol it relies on |
| NFR-5 | Observable: correlation ids in logs, health, delivery metrics | `TraceIdIT`, `StructuredLoggingIT`, `ActuatorExposureIT`, `DeliveryMetricsIT`, `DeliveryWorkerIT` (worker logs carry `traceId`), `DeliveryCallbacksIT` (callback outcomes counted), `IntakeMetricsIT` (`nms.notifications.submitted{outcome}`, `nms.dedup.lookup`), `RateLimitIT` (`nms.delivery.deferred`) |
| NFR-6 | Safe change management: issue -> PR -> CI -> review for every change | Process, not code: CI on every PR (`.github/workflows/ci.yml`); issue, PR, and review record per change (`docs/scenarios/greenfield.md`, Execution) |

## Deliverables

| ID | Deliverable | Evidence |
|---|---|---|
| D-1 | Working prototype, runnable end-to-end | `GreenfieldE2EIT`; `scripts/demo.sh` (run in CI against the started jar) |
| D-2 | Architecture overview: components, tools, execution approach, control flow, key decisions | `docs/architecture.md`, `docs/adr/` |
| D-3 | Three scenarios, each with decomposition, execution, validation | Greenfield: `docs/scenarios/greenfield.md`. Brownfield: `docs/scenarios/brownfield.md` (impact analysis, engineer decisions, Execution, Validation; #14), `docs/scenarios/brownfield-2.md` (#46). Ambiguous (#15): pending |
| D-4 | Setup instructions | Pending (#16); demo run in `README.md` |
| D-5 | Testing approach, limitations, trade-offs | Greenfield: `docs/scenarios/greenfield.md` (Validation, Limitations). Full write-up pending (#16) |
