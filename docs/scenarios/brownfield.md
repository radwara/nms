# Brownfield: event-level deduplication (impact analysis)

- Issue: #14. Requirements: SC-2, FR-4.3, FR-4.4. Ambiguity: A-04, A-35, A-36.
- Status: **Draft. Every recommendation below is a proposal for the engineer to decide.** No code is written until this document is reviewed and merged.
- Baseline: `main` at `97f9f03` (#6-#9, #12 merged). Worker (#10) and status/audit APIs (#11) are not built yet; their rows below use the planned contracts in `architecture.md` and the issue bodies. Re-check this analysis against the `v0.1.0-greenfield` tag before the code PR.

## 1. Change

A submission whose event was already notified to a recipient recently, under a **different** `Idempotency-Key`, is suppressed for that recipient: it gets a notification row but no deliveries, and the suppression shows in the submit response, status, and audit.

Why: spec 4.4 asks to prevent avoidable duplicate notifications. Greenfield only dedupes by idempotency key, so a source that loses its key (crash before storing it, a second instance emitting the same event, a batch job rerun) notifies the user again.

## 2. Current behaviour and gap

| Step | Request | Greenfield result |
|---|---|---|
| 1 | `Idempotency-Key: k1`, `eventId: evt-778`, `type: FRAUD_ALERT`, recipients `[user-ok]` | 202, `n1`, EMAIL + SMS deliveries |
| 2 | Same key `k1`, same body | 200, `n1` again, `DUPLICATE_SUBMISSION` on `n1`. Correct |
| 3 | **New key `k2`**, same body, 10 minutes later | 202, `n2`, two more deliveries. **user-ok gets a second SMS and email** |

Code path: `IntakeService.attempt` looks up only `idempotency_record (source_system, idempotency_key)`; nothing reads `notification.event_id`. There is no index on it.

## 3. Decisions to pin

### D1. Dedup key

| Option | Effect | Problem |
|---|---|---|
| a. `(source_system, event_id)` (A-04 literal) | One notification per event | A later request for the same event to a *different* recipient is suppressed |
| b. `(source_system, event_id, recipient_id)` | Per-recipient | A source that derives several notification types from one event (`ORDER_SHIPPED`, `ORDER_DELIVERED` for `order-123`) loses the second |
| **c. `(source_system, event_id, type, recipient_id)`** | Per recipient and type | Channels, severity, content are not part of the key (see below) |
| d. c + content hash | Suppresses only identical content | A corrected subject for the same event is sent twice; content hashing touches sensitive data |

**Proposal: c.** Rationale:
- `source_system` (from the API key) is required for tenant isolation: without it one tenant could suppress another's notifications by reusing its event ids.
- Per recipient matches the data model (one notification per recipient, ADR-0002) and keeps mixed batches possible.
- Not in the key: `channels`, `severity`, `priority`, `subject`, `body`, `scheduledAt`, `expiresAt`. A resubmission that only adds a channel or changes the text is still the same event; a genuinely new notification needs a new `eventId`. Documented trade-off.
- A-04 is updated to this key.

### D2. Window and configuration

| Option | Trade-off |
|---|---|
| 1h | Covers retry storms; misses a batch job rerun later that day |
| **24h** | Covers retries and same-day reruns; bounds the damage of a wrong suppression to one day |
| 7d (= idempotency retention) | Maximal protection; suppresses legitimate re-notifications for a week; larger index range |

**Proposal:**

```yaml
nms:
  dedup:
    enabled: ${NMS_DEDUP_ENABLED:true}   # false = greenfield behaviour, no lookup
    window: ${NMS_DEDUP_WINDOW:24h}      # must be positive; startup fails otherwise
```

- Duplicate if an **original** (non-suppressed) notification with the same key has `created_at > now - window` (strict: exactly `window` old is not a duplicate). `now` is the injected `Clock`, truncated to microseconds.
- Anchored on the original only: suppressed rows never extend the window, so a source resending every hour is not suppressed forever. Suppressed rows always point at an original (chains are one level deep).
- The window runs from submission time (`created_at`), not `scheduledAt`.

### D3. The original's outcome

| Option | Trade-off |
|---|---|
| **a. Suppress whatever the original's delivery outcome** | Decided at intake from intake data only; deterministic |
| b. Do not suppress if the original `FAILED` / `EXPIRED` / `NOT_ROUTED` | Lets a caller "retry" through dedup; reads delivery state that the worker is changing concurrently |

**Proposal: a.** Retrying failed sends is the worker's job (bounded retries, FR-5.1). A caller who wants a fresh send uses a new `eventId`.

### D4. Precedence

**Proposal** (order inside `attempt`, issue requires idempotency first):

1. Live idempotency record: same hash -> 200 replay; different hash -> 409 / 4091. Dedup is never consulted.
2. Lock-wait path unchanged (409 / 4092).
3. Time rules and unknown recipients -> 422, nothing written. An invalid request is rejected, not suppressed.
4. Claim the key (unchanged: before any content, so a same-key race still fails there).
5. **New:** one dedup lookup for all recipients, then split into originals and suppressed.
6. Insert notifications; audit; route originals only.

Consequence: a fully suppressed submission still claims its key, so a retry with that key replays the suppressed outcome (one key, one outcome).

### D5. Suppressed notification row

| Option | Problem |
|---|---|
| a. No row; return the original's `notificationId` | Breaks replay (`replayOrConflict` requires a row per recipient per `submissionId`, else `IllegalStateException`); mixes two submissions under one id |
| **b. Row with `duplicate_of uuid null`** = the original's id; no deliveries | One nullable column |
| c. Row with a `status` column | Contradicts "no status column, derived on read" |

**Proposal: b.** The marker is needed because "no deliveries" already means `NOT_ROUTED`; status cannot be derived from deliveries alone.
- No foreign key on `duplicate_of`, like `audit_event.notification_id`: originals are never deleted, and a self-FK would break `SubmitRequests.clearSubmissions` (`delete from notification` in one statement on H2).
- Content: the row keeps `subject` / `body` (columns stay `not null`) so no reader needs null handling. Privacy cost: one extra copy per suppressed recipient, never read. Production path: clear `body` on suppressed rows together with the existing "clear `body` once final" path. Alternative for the engineer: make both nullable and store nothing.

### D6. Submit response

**Proposal:** per-item `status` gains `SUPPRESSED`, plus `duplicateOf` (omitted when null).

```json
{
  "notifications": [
    { "recipientId": "user-ok", "notificationId": "9b2e...", "status": "SUPPRESSED", "duplicateOf": "6f1c..." },
    { "recipientId": "user-email-only", "notificationId": "a83d...", "status": "ACCEPTED" }
  ]
}
```

| Case | HTTP | Body |
|---|---|---|
| No recipient suppressed | 202 | Byte-identical to greenfield (no `duplicateOf` key) |
| Mixed batch | 202 | Request order; `ACCEPTED` or `SUPPRESSED` per item |
| All suppressed | 202 | All `SUPPRESSED`. Not 200 (that means replay) and not 409 (the key was claimed and rows written; callers treat 409 as failure) |
| Replay of any of the above (same key) | 200 | Same body as the original response, including `SUPPRESSED` items |

- `duplicateOf` is safe to return: the original has the same `source_system`.
- Replay needs `duplicate_of` in its projection (`SubmittedNotification` gains a field); still no entity loads.

### D7. Status and audit vocabulary

| Where | Proposal |
|---|---|
| `overallStatus` | New `SUPPRESSED`, **first** row of the derivation table (before `SCHEDULED` and `NOT_ROUTED`): `duplicate_of` set -> `SUPPRESSED`, `deliveries: []` |
| Status body (#11) | Adds `duplicateOf` (omitted when null). `duplicateSubmissions` keeps its #11 meaning (same-key replays of this notification) |
| Audit on the suppressed row | `SUPPRESSED`, detail `duplicate of <originalId>`. Its only submit-time event: no `ACCEPTED`, no routing events |
| Audit on the original | `DUPLICATE_EVENT`, detail `suppressed <newId>`. Makes the duplicate visible from the original's history, like `DUPLICATE_SUBMISSION` for replays |

Detail holds ids only, consistent with "audit and logs reference ids only". Alternative: audit only the suppressed row (saves one insert per suppressed recipient; the original's history stays silent).

### D8. Concurrent race

Two submissions with different keys for the same event commit concurrently: both lookups see nothing, both are accepted. A window cannot be a unique constraint.

| Option | Trade-off |
|---|---|
| **a. Document as a limitation** | Fails open: the race produces exactly greenfield behaviour (one duplicate), never a lost notification. Window is one submit transaction (milliseconds) |
| b. `select id from recipient where id in (:ids) order by id for update` before the lookup | One query, H2 + PostgreSQL. Serializes every submission per recipient (not only the same event) for the transaction; sorted ids avoid deadlocks; a lock timeout lands in the existing `PessimisticLockingFailureException` handler, which assumes an idempotency-key wait and would answer 4092 wrongly, so it needs its own handling and error code |
| c. Claim table `(source_system, event_id, type, recipient_id)` unique, insert or conditional update of an expired claim | Mirrors the idempotency claim; up to 100 conditional writes per submission |
| d. PostgreSQL `pg_advisory_xact_lock(hash(key))` per recipient | Exact per-event serialization; not available on H2 |

**Proposal: a** for the prototype, with d as the production path (b if H2 parity is required). Recorded in `architecture.md` limitations. Optional: a `RoutingGate` test that pins the limitation (both accepted), so a later serialization change is visible.

### D9. Idempotency retention

- Independent settings: `nms.idempotency.retention` (7d) answers "same key"; `nms.dedup.window` (24h) answers "same event, new key".
- Same key within retention: replay; dedup not consulted (D4).
- Same key after retention: a new submission (A-36), then dedup applies. With the defaults the key expires after the window, so this cannot suppress. If `window > retention`, the reused key is suppressed; consistent, documented, not rejected at startup.
- A replay of a suppressed submission stays `SUPPRESSED` even after the window passes (one key, one outcome).
- Production purge path: notifications must be kept at least `window` (and while referenced by `duplicate_of`).

## 4. Impact by layer

| Layer | Change | Files |
|---|---|---|
| Schema | `V3__event_dedup.sql`: `alter table notification add column duplicate_of uuid null`; `create index ix_notification_event_dedup on notification (source_system, event_id, type, recipient_id, created_at)`. Additive, no backfill (existing rows are originals). Production: build the index `concurrently` outside the Flyway transaction; PostgreSQL could use a partial index `where duplicate_of is null` (H2 has none) | new migration only; V1/V2 untouched |
| Intake | After `claimKey`: one projection query `select recipientId, id ... where sourceSystem, eventId, type, recipientId in (:ids), createdAt > :since, duplicateOf is null order by createdAt` (earliest original per recipient). Build rows with `duplicateOf`; `ACCEPTED` for originals, `SUPPRESSED` + `DUPLICATE_EVENT` for duplicates; route originals only. Replay projection adds `duplicateOf`. Logic stays in `intake` (no new feature package) | `IntakeService`, `Notification` (new field; existing constructor kept, overload added), `NotificationRepository`, `SubmittedNotification`, new `DedupProperties` in `IntakeConfiguration` |
| Routing | None. `RoutingStep.route` already receives "the new notifications"; intake passes only originals. All suppressed -> empty list (existing early return) | none |
| API | `SubmitResponse.Item` maps outcome to `ACCEPTED` / `SUPPRESSED`, `duplicateOf` with `@JsonInclude(NON_NULL)`. No new request fields, no validator change, `RequestHash` unchanged (so live idempotency records keep matching after deploy) | `SubmitResponse`, `NotificationController` (none if mapping stays in `SubmitResponse`) |
| Status / audit (#11) | `overallStatus` `SUPPRESSED` first; `duplicateOf` in status body. Audit read unchanged (`occurred_at, id`); new types `SUPPRESSED`, `DUPLICATE_EVENT` in `AuditEventType` | #11 status mapper and derivation; `AuditEventType`, `AuditRecorder` (two methods, `MANDATORY` like the rest) |
| Worker (#10) | None: suppressed rows have no deliveries, and the poll reads only `delivery`. Batch loads by `findAllById` never reach suppressed rows | none |
| Metrics | Counter `nms.notifications.suppressed`, incremented per suppressed recipient after commit. No tags: `type` and `eventId` are caller-defined (unbounded cardinality) | `IntakeService` (after-commit hook already exists) |
| Logs | After-commit line adds the suppressed count. No `eventId`, no recipient ids beyond what greenfield logs | `IntakeService` |
| Security | Key includes `source_system`: no cross-tenant suppression. No new PII; audit detail is ids | none |
| Docs | `architecture.md` (submit sequence, response, status table, audit types, data model, config, metrics, limitations); A-04 aligned to D1 + new row A-39 (window, precedence); ADR-0006 event dedup (D1, D5, D8 with rejected options); `requirements.md` Test column (SC-2, FR-4.3, FR-4.4); README and demo script step | docs |

## 5. Regression risk

| Risk | Mitigation |
|---|---|
| Existing tests share `eventId: "evt-1"` (`SubmitRequests.valid`, `IntakeServiceIT.command`, `RoutingIT.command`, `RoutingPolicyIT`) and could be suppressed | Checked: every submit test clears tables `@BeforeEach`, and within a test every repeated submission either reuses the key (replay) or follows an attempt that wrote nothing (400 / 422 / 500 rollback). `keysAreScopedPerSourceSystem` resubmits under another source system, which D1 keeps distinct. New dedup tests set their own `eventId` |
| `SubmitStatementsIT.createLoadsNoEntitiesAndBatchesInserts`: one more query; entity load count must stay 0; prepared statements `<= 25` | Projection query (no entity load); measure headroom in the refactor commit before the feature |
| Replay of greenfield submissions after deploy | `duplicate_of` is null -> `ACCEPTED`; body unchanged; hash unchanged |
| Response JSON for `ACCEPTED` items | `NON_NULL` on `duplicateOf`; asserted by a byte-level test |
| Code rollback after suppressed rows exist | Old code ignores `duplicate_of` (Hibernate `validate` allows extra columns); suppressed rows then read as `NOT_ROUTED`. Documented |
| Notification insert cost | One more index on `notification`; inserts stay batched |

Existing greenfield tests expected to change: **none**.

| Test | Why unchanged |
|---|---|
| `SubmitNotificationIT`, `SubmitValidationIT`, `SubmitRejectionAuditIT`, `SubmitReplayAfterExpiryIT`, `SubmitConcurrencyIT`, `SubmitLockTimeoutIT`, `IntakeServiceIT`, `RoutingIT`, `RoutingPolicyIT`, `RoutingRollbackIT` | No second accepted submission of the same (source, event, type, recipient) under a new key (see above) |
| `SubmitStatementsIT` | Within budget if the lookup is a projection (to verify; if the budget is exceeded, this is the one test to change, with the measured count as the reason) |
| `RequestHashTest`, `SubmitRequestValidatorTest`, `TimeViolationTest` | `SubmitCommand` and the request are unchanged |
| `NotificationMappingIT`, `TestEntities` | Existing `Notification` constructor kept |
| `PackageDependencyTest` | No new feature package |
| `SchemaMigrationIT` | Asserts versions contain 1 and 2; 3 is added |
| #10 / #11 tests (not written yet) | To confirm at the tag: an exhaustive `overallStatus` derivation test gains a `SUPPRESSED` row (addition, not change) |

## 6. Test plan

| Layer | Test | Asserts |
|---|---|---|
| Refactor commit | Full suite | Zero test changes, CI green |
| Unit | `DedupPropertiesTest` | Defaults `true` / `24h`; zero or negative window fails startup |
| Unit | #11 derivation test | `SUPPRESSED` wins over `SCHEDULED` and `NOT_ROUTED` |
| Unit | `SubmitResponseTest` | `SUPPRESSED` item carries `duplicateOf`; `ACCEPTED` item has no `duplicateOf` key |
| Intake IT | `EventDedupIT` | New key, same event and recipient within window -> `SUPPRESSED` row, `duplicate_of` = original, no deliveries, audits `SUPPRESSED` (new) and `DUPLICATE_EVENT` (original) |
| | | Boundary with `MutableClock`: `window - 1µs` suppressed; exactly `window` accepted |
| | | Different `type`, recipient, or source system -> accepted |
| | | Anchor: original at t0, duplicate at t0+20h suppressed, resubmit at t0+25h accepted |
| | | Original `NOT_ROUTED` still anchors (D3) |
| | | Mixed batch: request order kept; only originals get `ACCEPTED` and routing events |
| | | All suppressed: 202, no deliveries, no `ROUTED` / `QUEUED` |
| | | Replay of mixed batch with the same key: 200, identical body, `DUPLICATE_SUBMISSION` on every row, no new `SUPPRESSED` |
| | | Precedence: same key + different payload -> 409 / 4091; duplicate + unknown recipient -> 422, nothing written |
| | | `enabled: false` -> greenfield result and no dedup query executed |
| Statements | `SubmitStatementsIT` (new method) | 100 recipients: dedup lookup executes once; entity loads 0 |
| Concurrency (optional) | `EventDedupRaceIT` with `RoutingGate` | Pins D8: both concurrent submissions accepted |
| API IT | `SubmitDedupIT` | Response JSON shape for mixed batch; 202 for all-suppressed |
| Status / audit IT (#11) | | `GET` suppressed -> `SUPPRESSED`, `deliveries: []`, `duplicateOf`; other source system -> 404; audit order |
| Worker IT (#10) | | A suppressed submission leaves nothing due; poll sends nothing |
| Metrics | | Counter += suppressed recipients on commit; unchanged on rollback |
| Schema | `SchemaMigrationIT` (new method) | V3 applied; `ix_notification_event_dedup` exists |
| E2E | Demo script step | `evt-X` under `k1`, then `k2` -> `SUPPRESSED`; status of both |

## 7. Rollout

- Migration V3 is additive and runs before the code needs it; no backfill.
- Flag `nms.dedup.enabled` defaults to `true` (the enhancement is the point). `false` restores greenfield behaviour exactly: no lookup, no `SUPPRESSED`.
- Changing `window` takes effect on the next submission; no data change.
- API compatibility: additive only. New enum value `SUPPRESSED` in item `status` and `overallStatus`; new optional `duplicateOf`. Clients that check only the HTTP code (202) will not notice suppressed recipients: release note says to read per-item `status`. Clients with strict enum parsing need the new value.
- In-flight idempotency keys keep replaying: request hash unchanged.
- Rollback: see section 5 (suppressed rows read as `NOT_ROUTED` under old code).
- Code PR order (issue #14): behaviour-preserving refactor first (proposed: `SubmittedNotification` carries an outcome, always `ACCEPTED`, mapped by `SubmitResponse` instead of a constant; extract the new-submission branch of `IntakeService.attempt` into its own method), zero test changes, CI green. Then V3, feature, metric, docs.

## Engineer decisions

| Decision | Proposal | Engineer |
|---|---|---|
| D1 key | `(source_system, event_id, type, recipient_id)` | |
| D2 window | `24h`, `nms.dedup.enabled` / `nms.dedup.window` | |
| D3 original's outcome | Ignored | |
| D4 precedence | Replay / 409, then 422, then claim, then dedup | |
| D5 row | Yes, `duplicate_of`, no FK, content kept | |
| D6 response | 202, per-item `SUPPRESSED` + `duplicateOf` | |
| D7 vocabulary | `overallStatus` `SUPPRESSED`; audit `SUPPRESSED` + `DUPLICATE_EVENT` | |
| D8 race | Documented limitation; advisory lock as production path | |
| D9 retention | Independent settings | |
