# Brownfield: event-level deduplication (impact analysis)

- Issue: #14. Requirements: SC-2, FR-4.3, FR-4.4. Ambiguity: A-04, A-35, A-36.
- Status: **Merged #31; implemented in #41.** Engineer decisions below; D3 changed by the engineer in the #41 review (option B), and the lookup and index revised after the #41 performance review (section 4).
- Baseline: `main` at `be7ce96` (#6-#10, #12 merged); status, audit and callback APIs (#11) merged later at `3161dc7`, and their rows are re-checked against it (F1 decisions, section 4).
- Re-check at `v0.1.0-greenfield` (`667e70b`, before the code PR): every file in section 4 exists as described; `GreenfieldE2EIT` and the #13 demo use one `eventId` per flow or step, so neither can suppress another; no greenfield test needed a change (confirmed: the refactor commit has zero test changes and the feature only adds test methods). No finding changed the analysis.

## 1. Change

A submission that repeats an event **and** its message for a recipient within the window, under a **different** `Idempotency-Key`, is suppressed for that recipient when the earlier notification was routed (got at least one delivery) at intake: the repeat gets a notification row but no deliveries, and the suppression shows in the submit response, status, and audit. A different message for the same event, and a repeat of a `NOT_ROUTED` notification, are delivered.

Why: spec 4.4 asks to prevent avoidable duplicate notifications. Greenfield only dedupes by idempotency key, so a source that loses its key (crash before storing it, a batch job rerun) notifies the user again. Two instances emitting the same event *concurrently* are not covered: that is the race in D8.

## 2. Current behaviour and gap

| Step | Request | Greenfield result | With dedup |
|---|---|---|---|
| 1 | `Idempotency-Key: k1`, `eventId: evt-778`, `type: FRAUD_ALERT`, recipients `[user-ok]` | 202, `n1`, EMAIL + SMS deliveries | Same |
| 2 | Same key `k1`, same body | 200, `n1` again, `DUPLICATE_SUBMISSION` on `n1`. Correct | Same |
| 3 | **New key `k2`**, same body, 10 minutes later | 202, `n2`, two more deliveries. **user-ok gets a second SMS and email** | 202, `n2` `SUPPRESSED`, no deliveries |
| 4 | New key `k3`, same `eventId`, **different body** (second fraud alert) | 202, `n3`, delivered | Same: delivered |

Code path: `IntakeService.attempt` looks up only `idempotency_record (source_system, idempotency_key)`; nothing reads `notification.event_id`. There is no index on it.

## 3. Decisions

### D1. Dedup key (engineer decision)

**Key: `(source_system, event_id, type, recipient_id, content_hash)`.** Suppress only when the same event **and** the same message repeat within the window.

| Option | Outcome | Reason |
|---|---|---|
| a. `(source_system, event_id)` (A-04 literal) | Rejected | A later request for the same event to a *different* recipient is suppressed |
| b. a + `recipient_id` | Rejected | A source deriving several types from one event (`ORDER_SHIPPED`, `ORDER_DELIVERED` for `order-123`) loses the second |
| c. b + `type` (draft proposal) | Rejected by engineer | Spec 4.1 allows `eventId` to be a correlation id: a different alert reusing it (two fraud alerts for `acct-42` within 24h) would be dropped. A severity upgrade (`WARNING` then `CRITICAL`) would be suppressed (review M3) |
| c + `nms.dedup.mode: off \| shadow \| enforce` (review H1) | Rejected by engineer | Shadow mode only measures the risk; enforcing option c still drops the second alert. With the content hash a different message is always delivered, so there is nothing to shadow |
| c + "do not suppress a higher severity" (review M3) | Rejected by engineer | Subsumed: severity is in the hash, so any severity change is delivered |
| **d. c + `content_hash`** | **Decided** | Only an identical message is suppressed |

Accepted trade-off: a resend with changed text (a corrected subject, a reworded body) is not caught: it is delivered as a possible extra message. What is suppressed: an identical subject, body and severity for the same source, event, type and recipient, within the window, after a routed original, even if that original later `FAILED` or `EXPIRED` (D3).

`content_hash` canonical input:
- SHA-256, lowercase hex (64 chars), computed once per submission (subject, body, severity are request-level).
- Input: UTF-8 JSON object `{"body": ..., "severity": ..., "subject": ...}`, keys in that order, written by the shared `JsonMapper` (same approach as `RequestHash`). JSON quoting keeps field boundaries unambiguous (`("ab","c")` != `("a","bc")`).
- Values exactly as validated: no trimming or case folding (any difference is delivered). `severity` as the enum name.
- Not in the hash or key: `channels`, `priority`, `scheduledAt`, `expiresAt`. A resend that only adds a channel is the same message and is suppressed. Documented.

Other rationale:
- `source_system` (from the API key) keeps tenants isolated: one tenant cannot suppress another's notifications by reusing its event ids.
- Per recipient matches the data model (one notification per recipient, ADR-0002) and keeps mixed batches possible.
- A-04 is updated to this key.

### D2. Window and configuration

| Option | Trade-off |
|---|---|
| 1h | Covers retry storms; misses a batch job rerun later that day |
| **24h** | Covers retries and same-day reruns; bounds the damage of a wrong suppression to one day |
| 7d (= idempotency retention) | Maximal protection; suppresses legitimate re-notifications for a week; larger index range |

```yaml
nms:
  dedup:
    enabled: ${NMS_DEDUP_ENABLED:true}   # false = greenfield behaviour, no lookup
    window: ${NMS_DEDUP_WINDOW:24h}      # must be positive; startup fails otherwise
```

- Duplicate if a **routed original** (non-suppressed, at least one delivery created at intake; D3) notification with the same key has `created_at > now - window` (strict: exactly `window` old is not a duplicate). `now` is the injected `Clock`, truncated to microseconds.
- Anchored on the original only: suppressed rows never extend the window, so a source resending every hour is not suppressed forever. Suppressed rows always point at an original (chains are one level deep).
- The window runs from submission time (`created_at`), not `scheduledAt`.

### D3. The original's outcome

| Option | Trade-off |
|---|---|
| a. Suppress whatever the original's delivery outcome | Proposed. Decided at intake from intake data only; deterministic. A `NOT_ROUTED` original (nothing ever sent) blocks a resend after the address or preference is fixed |
| **B. Do not suppress if the original is `NOT_ROUTED`; `FAILED` / `EXPIRED` still anchor** | **Changed by engineer in the #41 review** ([review comment](https://github.com/radwara/nms/pull/41#discussion_r4350865614)) |
| b. Do not suppress if the original `FAILED` / `EXPIRED` / `NOT_ROUTED` | Lets a caller "retry" through dedup; reads delivery state that the worker is changing concurrently |

**B.** Reason: a `NOT_ROUTED` original sent nothing, so suppressing its resend would drop the only notification; `FAILED` and `EXPIRED` are asynchronous outcomes of an attempted send, and retrying them is the worker's job (bounded retries, FR-5.1). Implementation: `notification.routed` is set in the submit transaction (routing calls `Notification.markRouted()`, intake persists it with one bulk update), so the lookup filters on it without a join into `delivery`. A caller who wants a fresh send of a `FAILED` / `EXPIRED` notification uses a new `eventId`.

### D4. Precedence

Order inside `attempt` (issue requires idempotency first):

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

**b.** The marker is needed because "no deliveries" already means `NOT_ROUTED`; status cannot be derived from deliveries alone.
- No foreign key on `duplicate_of`: same convention as `audit_event.notification_id` (references by id, no FK), and the production purge path can remove an original without first deleting or rewriting the suppressed rows that point at it. `duplicate_of` is informational; a dangling value is returned as-is and a `GET` on it returns 404.
- Every new row (original or suppressed) stores `content_hash`; lookups exclude suppressed rows via `duplicate_of is null`.
- Content: the row keeps `subject` / `body` (columns stay `not null`) so no reader needs null handling. Privacy cost: one extra copy per suppressed recipient, never read. Production path: clear `body` on suppressed rows together with the existing "clear `body` once final" path. Alternative: make both nullable and store nothing.

### D6. Submit response

Per-item `status` gains `SUPPRESSED`, plus `duplicateOf` (omitted when null).

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

| Where | Decision |
|---|---|
| `overallStatus` | New `SUPPRESSED`, **first** row of the derivation table (before `SCHEDULED` and `NOT_ROUTED`): `duplicate_of` set -> `SUPPRESSED`, `deliveries: []` |
| Status body (#11) | Adds `duplicateOf`, serialized as `null` when not suppressed, like `requestedChannels` (status top-level fields are always present; the submit item keeps omitting it). `duplicateSubmissions` keeps its #11 meaning (same-key replays of this notification) |
| Audit on the suppressed row | `SUPPRESSED`, detail `duplicate of <originalId>`. Its only submit-time event: no `ACCEPTED`, no routing events |
| Audit on the original | `DUPLICATE_EVENT`, detail `suppressed <newId>`. Makes the duplicate visible from the original's history, like `DUPLICATE_SUBMISSION` for replays |

Detail holds ids only (never the hash or content), consistent with "audit and logs reference ids only". Alternative: audit only the suppressed row (saves one insert per suppressed recipient; the original's history stays silent).

### D8. Concurrent race

Two submissions with different keys for the same event and message commit concurrently: both lookups see nothing, both are accepted. A window cannot be a unique constraint.

| Option | Trade-off |
|---|---|
| **a. Document as a limitation** | Fails open: the race produces exactly greenfield behaviour (one extra message); it never suppresses a different message. Window is one submit transaction (milliseconds) |
| b. `select id from recipient where id in (:ids) order by id for update` before the lookup | One query, H2 + PostgreSQL. Serializes every submission per recipient (not only the same event). Sorted ids avoid deadlocks |
| c. Claim table on the D1 key, unique; insert or conditional update of an expired claim | Mirrors the idempotency claim; up to 100 conditional writes per submission |
| d. PostgreSQL `pg_advisory_xact_lock(hash(key))` per recipient | Exact per-event serialization; not available on H2 |

Caveats for b-d (none applies to a):
- Lock failures (b, d) surface as `PessimisticLockingFailureException`, which the existing handler treats as an idempotency-key wait: it finds no record and answers **409 / 4092** wrongly. Needs its own catch and error code.
- d: acquire the per-recipient locks in sorted key order, or two overlapping batches deadlock. PostgreSQL waits forever by default, so `lock_timeout` must be set (already a production item for the 4092 path). Hash collisions only over-serialize.
- c: a lost claim race is a `DataIntegrityViolationException`; the existing handler finds no same-key record and rethrows, so the caller gets **500** unless handled.

**a** for the prototype, with d as the production path (b if H2 parity is required). Reason: it fails open (an extra message, never a suppressed different one) and needs no new lock handling. Recorded in `architecture.md` limitations. Optional: a `RoutingGate` test that pins the limitation (both accepted), so a later serialization change is visible.

### D9. Idempotency retention

- Independent settings: `nms.idempotency.retention` (7d) answers "same key"; `nms.dedup.window` (24h) answers "same event and message, new key".
- Same key within retention: replay; dedup not consulted (D4).
- Same key after retention: a new submission (A-36), then dedup applies. With the defaults the key expires after the window, so this cannot suppress. If `window > retention`, the reused key is suppressed; consistent, documented, not rejected at startup.
- A replay of a suppressed submission stays `SUPPRESSED` even after the window passes (one key, one outcome).
- Production purge path: keep notifications at least `window`; suppressed rows may outlive their original (D5).

## 4. Impact by layer

| Layer | Change | Files |
|---|---|---|
| Schema | `V3__event_dedup.sql`: `alter table notification add column duplicate_of uuid null`; `add column content_hash char(64) null` (null only on pre-V3 rows; every new row writes it); `add column routed boolean default false not null` (D3, #41 review); `create index ix_notification_event_dedup on notification (source_system, event_id, type, content_hash, duplicate_of, routed, created_at)` (revised in the #41 performance review: the first version led with `recipient_id` and filtered `duplicate_of` after the index, so every suppressed repeat was read, 85 ms at 100k rows on H2). Additive, no backfill: pre-V3 rows never match (null hash, not routed). Verified on H2 only. Production (PostgreSQL): a partial index `where duplicate_of is null and routed`, built `concurrently` in its own non-transactional migration (H2 has no partial indexes) | new migration only; V1/V2 untouched |
| Intake | Compute `content_hash` once per submission. After `claimKey`: one projection query `select recipientId, id ... where sourceSystem, eventId, type, contentHash, duplicateOf is null, routed = true, createdAt > :since order by createdAt` (earliest original per recipient; recipients matched in `EventDedup`, not with `recipient_id in (:ids)`, #41 review), timed by `nms.dedup.lookup`. Build rows with `contentHash` and `duplicateOf`; `ACCEPTED` for originals, `SUPPRESSED` + `DUPLICATE_EVENT` for duplicates; route originals only. Replay projection adds `duplicateOf`. The lookup lives in a package-private `EventDedup` class in `intake` (introduced by the refactor commit), so no feature package is added. Deviation: the originals / suppressed split and their audits live in `IntakeService.create`, next to the inserts they depend on | `IntakeService`, new `EventDedup`, new `ContentHash` (package-private, next to `RequestHash`), `Notification` (new fields; existing constructor kept, overload added), `NotificationRepository`, `SubmittedNotification`, new `DedupProperties` in `IntakeConfiguration` |
| Routing | `RoutingStep.route` already receives "the new notifications"; intake passes only originals. All suppressed -> empty list (existing early return). Added in the #41 review (D3): `ChannelRouter` calls `Notification.markRouted()` for a notification that gets a delivery | `ChannelRouter`, `RoutingStep` (contract) |
| API | `SubmitResponse.Item` maps outcome to `ACCEPTED` / `SUPPRESSED`, `duplicateOf` with `@JsonInclude(NON_NULL)`. No new request fields, no validator change, `RequestHash` unchanged (so live idempotency records keep matching after deploy) | `SubmitResponse`, `NotificationController` (none if mapping stays in `SubmitResponse`) |
| Status / audit (#11) | `overallStatus` `SUPPRESSED` first via `OverallStatus.derive(boolean suppressed, scheduledAt, now, deliveries)`; `duplicateOf` in status body (null when not suppressed). Audit read unchanged (`occurred_at, id`); new types `SUPPRESSED`, `DUPLICATE_EVENT` in `AuditEventType` | `OverallStatus`, `NotificationStatus`, `StatusResponse`; `AuditEventType`, `AuditRecorder` (two methods, `MANDATORY` like the rest) |
| Callbacks (#11) | None: callbacks resolve by `deliveryId`; suppressed rows have no deliveries, so no callback can reach them | none |
| Worker (#10) | None: suppressed rows have no deliveries, and the poll reads only `delivery` | none |
| Metrics | Counter `nms.notifications.submitted{outcome=accepted\|suppressed}`, incremented per notification after commit (gives the denominator). Alert on the suppression ratio `suppressed / (accepted + suppressed)` above its baseline (e.g. 20% over 15 min): a source in a resend loop, or dedup suppressing more than expected. No `type` / `eventId` tags (caller-defined, unbounded cardinality) | New `IntakeMetrics` class in `intake` (like `DeliveryMetrics` in `delivery`), called from `IntakeService`'s existing after-commit hook |
| Logs | After-commit line keeps its prefix and appends the suppressed count: `Submission {} accepted: {} notifications, {} suppressed, for source system {}` (`accepted` counts originals). No `eventId`, hash, or recipient ids beyond what greenfield logs | `IntakeService` |
| Security | Key includes `source_system`: no cross-tenant suppression. `content_hash` is an unsalted SHA-256 of short, partly predictable text (guessable by brute force), so it is never returned, logged, or audited; production path: HMAC with a server-held key. No new PII; audit detail is ids | none |
| F1 decisions (#11 re-check at `3161dc7`) | (1) `OverallStatus` gains `derive(boolean suppressed, Instant scheduledAt, Instant now, Collection<DeliveryStatus> deliveries)`; the existing 3-arg form delegates with `suppressed = false`, so `OverallStatusTest` is unchanged (new cases are additions). (2) `duplicateOf` in the status body is serialized as `null`, like `requestedChannels`. (3) Metrics live in a new `IntakeMetrics` class in `intake` | see rows above |
| Docs | `architecture.md` (submit sequence, response, status table, audit types, data model incl. `content_hash` and its canonical input, config, metrics + "how to operate" alert, limitations incl. D8 caveats); A-04 aligned to D1; A-35 gains the event-level boundary (one original per D1 key per window); A-36 notes the dedup window is separate from key retention; new row A-39 (window, precedence, content hash); ADR-0006 event dedup (D1, D5, D8 with rejected options), linked from ADR-0002; `requirements.md` Test column (SC-2, FR-4.3, FR-4.4); README and demo script step | docs |

## 5. Regression risk

| Risk | Mitigation |
|---|---|
| Existing tests share `eventId: "evt-1"` (`SubmitRequests.valid`, `IntakeServiceIT.command`, `RoutingIT.command`, `RoutingPolicyIT`) and could be suppressed | Checked: every submit test clears tables `@BeforeEach`, and within a test every repeated submission either reuses the key (replay) or follows an attempt that wrote nothing (400 / 422 / 500 rollback). `keysAreScopedPerSourceSystem` resubmits under another source system, which D1 keeps distinct. The content hash only narrows matches further. New dedup tests set their own `eventId` |
| `SubmitStatementsIT` prepared-statement budgets | Measured on `main` (`be7ce96`): create path **18 of 25**, replay **5 of 10**. Dedup adds the lookup and (#41 review, D3) one bulk `routed` update on create; replay only gains a projected column. Budgets asserted unchanged; entity loads stay 0 (projection query) |
| Replay of greenfield submissions after deploy | `duplicate_of` is null -> `ACCEPTED`; body unchanged; hash unchanged |
| Pre-V3 rows have no `content_hash` | They never anchor: in the first window after deploy, a resend of a pre-deploy event is delivered (greenfield behaviour) |
| Response JSON for `ACCEPTED` items | `NON_NULL` on `duplicateOf`; asserted by a byte-level test |
| Code rollback after suppressed rows exist | The schema is forward-only: a code rollback keeps `V3__event_dedup.sql` in the deployed migrations (removing an applied migration fails Flyway validate). Old code ignores the new columns (Hibernate `validate` allows extra columns; `routed` has a default). Suppressed rows then read as `NOT_ROUTED` in status, and a same-key replay reports them as `ACCEPTED` (old `SubmitResponse` maps a constant) although nothing was sent. Prefer `nms.dedup.enabled=false` over a code rollback; document both |
| Notification insert cost | One more index on `notification`; inserts stay batched |

Existing greenfield tests expected to change: **none**.

| Test | Why unchanged |
|---|---|
| `SubmitNotificationIT`, `SubmitValidationIT`, `SubmitRejectionAuditIT`, `SubmitReplayAfterExpiryIT`, `SubmitConcurrencyIT`, `SubmitLockTimeoutIT`, `RoutingIT`, `RoutingPolicyIT`, `RoutingRollbackIT` | No second accepted submission of the same D1 key under a new key (see above) |
| `IntakeServiceIT` | As above; also asserts the after-commit log contains `accepted: 1 notifications` exactly once. The new line keeps that prefix |
| `SubmitStatementsIT` | Measured headroom above; budgets unchanged |
| `RequestHashTest`, `SubmitRequestValidatorTest`, `TimeViolationTest` | `SubmitCommand` and the request are unchanged |
| `NotificationMappingIT`, `TestEntities` | Existing `Notification` constructor kept |
| `PackageDependencyTest` | `EventDedup` and `ContentHash` stay in `intake`; no new feature package |
| `SchemaMigrationIT` | Asserts versions contain 1 and 2; 3 is added |
| #10 / #11 tests | `OverallStatusTest` calls the 3-arg `derive`, which delegates with `suppressed = false` (F1); `SUPPRESSED` cases are added in a new method, no existing case changes. `NotificationStatusIT` asserts named fields, so a new `duplicateOf: null` field changes none of its assertions |
| `GreenfieldE2EIT` | Each flow uses a different recipient, so no two accepted submissions share a D1 key; the duplicate flow reuses its `Idempotency-Key` (a replay, which dedup never sees) |
| `scripts/demo.sh` (CI) | Each step has its own `eventId` (`<run>-<step>`) and every run a new run id, so no step can suppress another; the brownfield step adds its own `eventId` |

## 6. Test plan

| Layer | Test | Asserts |
|---|---|---|
| Refactor commit | Full suite | Zero test changes, CI green |
| Unit | `DedupPropertiesTest` | Defaults `true` / `24h`; zero or negative window fails startup |
| Unit | `ContentHashTest` | Known vector; 64 lowercase hex; unchanged by `eventId`, `channels`, `priority`, `scheduledAt`, recipients; changes on any change to subject, body, or severity; `("ab","c")` != `("a","bc")` |
| Unit | #11 derivation test | `SUPPRESSED` wins over `SCHEDULED` and `NOT_ROUTED` |
| Unit | `SubmitResponseTest` | `SUPPRESSED` item carries `duplicateOf`; `ACCEPTED` item has no `duplicateOf` key |
| Intake IT | `EventDedupIT` | New key, same event, message and recipient within window -> `SUPPRESSED` row, `duplicate_of` = original, no deliveries, audits `SUPPRESSED` (new) and `DUPLICATE_EVENT` (original) |
| | | Same event, different body or subject -> accepted (step 4) |
| | | Same event, `WARNING` then `CRITICAL` -> accepted (review M3) |
| | | Different `type`, recipient, or source system -> accepted |
| | | Boundary with `MutableClock`: `window - 1µs` suppressed; exactly `window` accepted |
| | | Anchor: original at t0, duplicate at t0+20h suppressed, resubmit at t0+25h accepted |
| | | `NOT_ROUTED` original does not anchor; `FAILED` and `EXPIRED` originals do (D3, changed in #41); pre-V3 row with null `content_hash` never anchors |
| | | Mixed batch: request order kept; only originals get `ACCEPTED` and routing events |
| | | All suppressed: 202, no deliveries, no `ROUTED` / `QUEUED` |
| | | Replay of mixed batch with the same key: 200, identical body, `DUPLICATE_SUBMISSION` on every row, no new `SUPPRESSED` |
| | | Precedence: same key + different payload -> 409 / 4091; duplicate + unknown recipient -> 422, nothing written |
| | | `enabled: false` -> greenfield result and no dedup query executed; `content_hash` still written |
| Statements | `SubmitStatementsIT` (new method) | 100 recipients: dedup lookup executes once; entity loads 0; prepared statements stay within 25 |
| Concurrency (optional) | `EventDedupRaceIT` with real routing held in its transaction | Pins D8: both concurrent submissions accepted |
| API IT | `SubmitDedupIT` | Response JSON shape for mixed batch; 202 for all-suppressed |
| Status / audit IT (#11) | | `GET` suppressed -> `SUPPRESSED`, `deliveries: []`, `duplicateOf`; other source system -> 404; audit order |
| Worker IT (#10) | | A suppressed submission leaves nothing due; poll sends nothing |
| Metrics | | `nms.notifications.submitted{outcome}` += per notification on commit; unchanged on rollback |
| Schema | `SchemaMigrationIT` (new method) | V3 applied; `duplicate_of`, `content_hash`, `ix_notification_event_dedup` exist |
| E2E | Demo script step | `evt-X` under `k1`, then `k2` -> `SUPPRESSED`; `k3` with a different body -> `ACCEPTED`; status of all three |

## 7. Rollout

- Migration V3 is additive and runs before the code needs it; no backfill (pre-V3 rows never anchor, see section 5).
- No shadow mode (D1). `nms.dedup.enabled` (default `true`) is the off switch: `false` restores greenfield behaviour (no lookup, no `SUPPRESSED`); `content_hash` is still written, so re-enabling works at once.
- `enabled` and `window` are read at startup: a change takes effect on restart; no data change.
- API compatibility: additive only. New enum value `SUPPRESSED` in item `status` and `overallStatus`; new optional `duplicateOf`. Clients that check only the HTTP code (202) will not notice suppressed recipients: release note says to read per-item `status`. Clients with strict enum parsing need the new value.
- In-flight idempotency keys keep replaying: request hash unchanged.
- Rollback: turn dedup off first. A code rollback keeps V3 (forward-only schema; Flyway validate fails if an applied migration is missing) and makes replays report suppressed items as `ACCEPTED` (section 5).
- Code PR order (issue #14): behaviour-preserving refactor first (`SubmittedNotification` carries an outcome, always `ACCEPTED`, mapped by `SubmitResponse` instead of a constant; extract the new-submission branch of `IntakeService.attempt` into its own method; add package-private `EventDedup` that returns every recipient as an original), zero test changes, CI green. Then V3, feature, metric, docs.

## Engineer decisions

| Decision | Proposal | Engineer |
|---|---|---|
| D1 key | `(source_system, event_id, type, recipient_id, content_hash)`; hash over subject, body, severity | Changed from the draft's `(source_system, event_id, type, recipient_id)`; shadow mode and severity rule rejected ([review comment](https://github.com/radwara/nms/pull/31#discussion_r4344120633)) |
| D2 window | `24h`, `nms.dedup.enabled` / `nms.dedup.window` | Accepted |
| D3 original's outcome | Ignored | Changed by engineer: option B, a `NOT_ROUTED` original does not anchor; `FAILED` / `EXPIRED` do ([review comment](https://github.com/radwara/nms/pull/41#discussion_r4350865614)) |
| D4 precedence | Replay / 409, then 422, then claim, then dedup | Accepted |
| D5 row | Yes, `duplicate_of`, no FK, content kept | Accepted |
| D6 response | 202, per-item `SUPPRESSED` + `duplicateOf` | Accepted |
| D7 vocabulary | `overallStatus` `SUPPRESSED`; audit `SUPPRESSED` + `DUPLICATE_EVENT` | Accepted |
| D8 race | Documented limitation; advisory lock as production path | Accepted |
| D9 retention | Independent settings | Accepted |

## Execution

Analysis: PR #31 (merged). Code: PR #41 on `feat/14-event-dedup`, from `v0.1.0-greenfield` (`667e70b`).

| # | Commit | Content |
|---|---|---|
| 1 | `3d9a72c` refactor(intake): add event dedup and status seams, no-op | **Behaviour-preserving refactor.** `SubmittedNotification.outcome()` (always `ACCEPTED`) mapped by `SubmitResponse`; new-submission branch extracted to `IntakeService.create`; package-private `EventDedup` returning no duplicates; `OverallStatus.derive(boolean suppressed, ...)` with the 3-arg form delegating. Local `./mvnw -B clean verify` green (273 unit, 249 integration); CI green on draft PR #42 at `3d9a72c` (closed): https://github.com/radwara/nms/actions/runs/39806036157 |
| 2 | `c21c02c` feat(db): V3 notification dedup columns and index | `V3__event_dedup.sql`, `Notification` fields and overload constructor |
| 3 | `99dc84d` feat(intake): suppress repeated events within the dedup window | `ContentHash`, `DedupProperties`, `EventDedup` lookup, suppressed rows, audits, `IntakeMetrics`, config |
| 4 | `c50e693` feat(api): SUPPRESSED in submit response, status, and audit | `SUPPRESSED` overall status, `duplicateOf` in status |
| 5 | `5ebacd7` feat(demo): event dedup step with a suppressed repeat | `scripts/demo.sh` step 6 |
| 6-10 | docs | ADR-0006, `architecture.md`, A-04 / A-35 / A-36 / A-39, `requirements.md`, README, this section |
| 11 | `c440e93` fix(intake): routed-only dedup anchor and storm-safe lookup | #41 review: D3 option B (`routed` flag in V3, set by routing, persisted by one bulk update); lookup without `recipient_id in (:ids)`, index `(source_system, event_id, type, content_hash, duplicate_of, routed, created_at)`; `nms.dedup.lookup` timer; `in_clause_parameter_padding`; the 16-arg `Notification` constructor requires a content hash; `EventOriginal` lookup row. V3 edited in place (not merged) |
| 12+ | docs | #41 review: D3, index, rollback, and wording updates |

Zero test changes in the refactor commit:

```
$ git diff --stat v0.1.0-greenfield..3d9a72c -- src/test
(empty)
```

Greenfield tests changed: none. Three files gained new methods only, as planned in section 6 (`git diff v0.1.0-greenfield -- src/test` has no removed lines): `OverallStatusTest`, `SubmitStatementsIT`, `SchemaMigrationIT`.

Deviations from this analysis:
- D3 changed by the engineer (option B) and the lookup / index revised, both in the #41 review (sections 3 and 4).
- The originals / suppressed split and their audits live in `IntakeService.create`, not in `EventDedup`.
- Routing changed: `ChannelRouter` marks routed notifications (D3).
- Small additions: `SubmittedNotification.Outcome` enum; `RequestHash.sha256Hex` shared with `ContentHash`; the optional `EventDedupRaceIT` included.
- V3 verified on H2 only (tests and demo); not run against PostgreSQL.

| PR | Review | Findings | Outcome |
|---|---|---|---|
| _TBD_ | _TBD_ | _TBD_ | _TBD_ |

## Validation

| Test | Covers |
|---|---|
| `ContentHashTest` | Known vector, 64 lowercase hex; ignores event, type, recipients, channels, priority, times; changes on subject, body, severity; no trimming; `("ab","c")` != `("a","bc")` |
| `DedupPropertiesTest` | Defaults `true` / `24h`; binding; zero or negative window fails startup |
| `NotificationTest` | A new row requires a content hash; `markRouted` on an original; a suppressed row cannot be routed |
| `OverallStatusTest` (new methods) | `SUPPRESSED` wins over `SCHEDULED`, `NOT_ROUTED`, any deliveries; `suppressed = false` equals the greenfield table |
| `SubmitResponseTest` | `ACCEPTED` item byte-identical to greenfield (no `duplicateOf`); `SUPPRESSED` item carries it |
| `EventDedupIT` | Suppressed row, `duplicate_of`, same hash, no deliveries, `SUPPRESSED` / `DUPLICATE_EVENT` audits; different subject or body, `WARNING` -> `CRITICAL`, other type / recipient / source / event delivered; window strict (`window - 1µs` suppressed, `window` delivered); anchored on the original (20h suppressed, 25h delivered); `NOT_ROUTED` original does not anchor and its routed resend does; `FAILED` and `EXPIRED` originals anchor (worker run); channels and `expiresAt` not in the key; pre-V3 row (null hash) never anchors; mixed batch order and routing; fully suppressed; replay after the window identical, no new `SUPPRESSED`; 409 / 422 before dedup; no content or hash in logs or audit |
| `EventDedupLookupIT` | Storm data (2,000 suppressed repeats, 2,000 unrouted rows, 2,000 other events): the lookup returns only the routed original; `EXPLAIN ANALYZE` uses `ix_notification_event_dedup` with `scanCount` <= 10 |
| `EventDedupDisabledIT` | `enabled=false`: repeat delivered, no dedup query, hash still written |
| `SubmitStatementsIT` (new method) | 100 recipients: one dedup lookup per submission, 0 entity loads, <= 25 statements |
| `IntakeMetricsIT` | `nms.notifications.submitted{outcome}` per notification on commit; replay and rollback not counted; `nms.dedup.lookup` once per new submission |
| `SchemaMigrationIT` (new method) | V3 applied; nullable dedup columns; `routed` not null default false; index column order |
| `SubmitDedupIT` (MockMvc, no server) | Mixed batch shape; all suppressed 202, replay 200 same body; status `SUPPRESSED`, `deliveries: []`, `duplicateOf`; original `duplicateOf: null`; audit API; other tenant 404 and never suppressed; worker sends nothing for the suppressed row |
| `EventDedupRaceIT` (real HTTP, real routing) | Pins D8: two overlapping repeats under different keys are both accepted; a later one is suppressed |
| `scripts/demo.sh` step 6 (real HTTP, dev-profile jar) | Suppressed repeat, delivered new message, status and audit of all three |

`./mvnw -B clean verify`: 312 unit, 275 integration, 0 failures (after the #41 review fixes).

Demo (`scripts/demo.sh` against the dev-profile jar), step 6:

```
== 6. Event dedup: same event and message under a new key is suppressed; a new message is delivered
   POST /api/v1/notifications  key=demo-1792643895-24833-dedup-1  -> 202
        user-ok ACCEPTED 0c4fd20d-...
   POST /api/v1/notifications  key=demo-1792643895-24833-dedup-2  -> 202
        user-ok SUPPRESSED a13d0139-...  duplicateOf=0c4fd20d-...
   POST /api/v1/notifications  key=demo-1792643895-24833-dedup-3  -> 202
        user-ok ACCEPTED fa914b5d-...
   GET  /api/v1/notifications/a13d0139-...  -> 200  user-ok  overallStatus=SUPPRESSED  requestedChannels=none  duplicateSubmissions=0  duplicateOf=0c4fd20d-...
   GET  /api/v1/notifications/a13d0139-.../audit  -> 200
        SUPPRESSED
   GET  /api/v1/notifications/0c4fd20d-.../audit  -> 200
        ACCEPTED ROUTED(EMAIL) ROUTED(SMS) QUEUED(EMAIL) QUEUED(SMS) DUPLICATE_EVENT SENT(EMAIL)
        SENT(SMS) DELIVERED(EMAIL) DELIVERED(SMS)
   GET  /api/v1/notifications/fa914b5d-...  -> 200  user-ok  overallStatus=DELIVERED  requestedChannels=none  duplicateSubmissions=0
   OK
...
Demo passed: 9 steps.
```
