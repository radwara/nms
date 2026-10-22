# ADR-0006: Event-level deduplication

- Status: Accepted (2026-10-21); revised in the #41 review (2026-10-22): D3 option B, lookup and index
- Issue: #14. Impact analysis: `docs/scenarios/brownfield.md` (PR #31). Code: PR #41
- Related: ADR-0002, A-04, A-35, A-36, A-39

## Context

Spec 4.4 asks to prevent avoidable duplicate notifications. Greenfield dedupes only by `Idempotency-Key` (ADR-0002): a source that loses its key (crash before storing it, a batch rerun) notifies the user again. Spec 4.1 allows `eventId` to be a correlation id, so one event id can legitimately carry several different alerts.

## Options

Dedup key:

| Option | Outcome |
|---|---|
| a. `(source_system, event_id)` (A-04 literal) | Rejected: a later request for the same event to another recipient is suppressed |
| b. a + `recipient_id` | Rejected: a source deriving several types from one event loses the second |
| c. b + `type` | Rejected: a second, different alert reusing a correlation id is dropped; a `WARNING` then `CRITICAL` upgrade is suppressed |
| c + shadow mode (`off \| shadow \| enforce`) | Rejected: shadow only measures the risk of c; enforcing c still drops the second alert |
| c + "never suppress a higher severity" | Rejected: subsumed by d (severity is in the hash) |
| **d. c + `content_hash`** | **Decided**: only an identical subject, body and severity is suppressed |

Suppressed recipient:

| Option | Outcome |
|---|---|
| No row; return the original's id | Rejected: breaks replay (one row per recipient per submission) and mixes two submissions under one id |
| Row with a `status` column | Rejected: contradicts "no status column, derived on read" |
| **Row with `duplicate_of`, no deliveries** | **Decided** |

The original's outcome:

| Option | Outcome |
|---|---|
| a. Any outcome anchors | Rejected by engineer in the #41 review: a `NOT_ROUTED` original sent nothing, so its resend (after the address or preference is fixed) would be dropped |
| **B. Routed originals anchor; `NOT_ROUTED` does not** | **Decided** ([review comment](https://github.com/radwara/nms/pull/41#discussion_r4350865614)) |
| b. Only `DELIVERED` / in-flight originals anchor | Rejected: lets callers retry `FAILED` / `EXPIRED` sends through dedup; reads delivery state the worker is changing concurrently |

Concurrent race (a window cannot be a unique constraint):

| Option | Outcome |
|---|---|
| **Document as a limitation** | **Decided**: fails open (one extra message, greenfield behaviour); it never suppresses a different message |
| Lock the recipient rows (`for update`, sorted) | Serializes every submission per recipient; lock timeouts would wrongly map to 409 / 4092 |
| Claim table on the key | Up to 100 conditional writes per submission; a lost claim is a 500 unless handled |
| PostgreSQL `pg_advisory_xact_lock` per key | Exact; not on H2. Production path |

## Decision

**d**, with a suppressed row and the race documented.

- Key `(source_system, event_id, type, recipient_id, content_hash)`; `content_hash` = SHA-256 of `{"body","severity","subject"}` (shared `JsonMapper`, values as validated). Not in it: channels, priority, times.
- Suppressed: a repeat of that key, under a new `Idempotency-Key`, within the window, of an original that was **routed** at intake (at least one delivery created), whatever that original's later outcome (`FAILED` and `EXPIRED` still anchor: retries are the worker's job). Delivered: any other message, any repeat of a `NOT_ROUTED` original, and the race below.
- `notification.routed` is set in the submit transaction: `ChannelRouter` calls `Notification.markRouted()`, intake persists it with one bulk update, so the lookup needs no join into `delivery`.
- Window `nms.dedup.window` (default `24h`), strict, from the original's `created_at`; suppressed rows never anchor. `nms.dedup.enabled=false` is the off switch; no shadow mode.
- Order: live idempotency record (replay / 409) first, then 422, then claim the key, then one dedup lookup for all recipients.
- Response 202 with per-item `SUPPRESSED` + `duplicateOf`; `overallStatus` `SUPPRESSED`; audit `SUPPRESSED` (new row) and `DUPLICATE_EVENT` (original); metric `nms.notifications.submitted{outcome}`.
- V3: nullable `duplicate_of` (no FK) and `content_hash`, `routed boolean default false not null`, index `(source_system, event_id, type, content_hash, duplicate_of, routed, created_at)`. One lookup per submission without a recipient filter in SQL (recipients matched in memory), so suppressed repeats and unrouted rows are skipped by the index (#41 performance review: the first index, led by `recipient_id` and filtering `duplicate_of` after it, read every suppressed repeat). No backfill: pre-V3 rows never match. Verified on H2 only; PostgreSQL production variant: a partial index `where duplicate_of is null and routed`, built `concurrently` in its own non-transactional migration.

## Discussion

- Draft proposed option c. Independent review flagged the correlation-id case and suggested shadow mode (H1) and a severity rule (M3).
- Engineer rejected both and changed the key to d: with the hash a different message is always delivered, so there is nothing to shadow ([review comment](https://github.com/radwara/nms/pull/31#discussion_r4344120633)).
- Engineer accepted the remaining proposals (window, precedence, row, response, vocabulary, race, retention) as written in the impact analysis.
- #41 review: engineer changed D3 to option B (a `NOT_ROUTED` original does not anchor). Performance review measured the first lookup on H2 and PostgreSQL 17 (85 ms at 100k rows on H2, which never used V3 as designed); the recipient `IN` list was dropped and the index reordered.

## Consequences

- Plus: a lost key no longer re-notifies within the window; a message that differs in subject, body or severity is always delivered; a resend of a never-routed notification is delivered; every suppression is visible in the response, status, audit, and metric.
- Minus: a resend of a `FAILED` or `EXPIRED` original's identical message is suppressed within the window (use a new `eventId`); a resend with reworded text is not caught; two overlapping submissions both pass; one extra content copy per suppressed row; one bulk update per submission for `routed`; clients reading only the HTTP code miss suppressed recipients (release note: read per-item `status`).
