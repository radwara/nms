# Ambiguity Log

Gaps and unclear points in the spec, with the assumption taken. `Proposed` = drafted, awaiting engineer sign-off. `Accepted` = signed off (remaining drafts accepted at design freeze, 2026-10-20). `Asked` = sent to the hiring team. `Open` = worked as a scenario.

## Spec-level

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-01 | Sections 4.6-4.8 are missing (numbering jumps 4.5 -> 4.9) | Do not invent requirements. Ask the hiring team; proceed with what is written | Asked |
| A-02 | Section 3.3 (ambiguous scenario) has no content | Choose a realistic ambiguous requirement ourselves (see A-30) | Asked |
| A-03 | PDF says 2-3 days; email says 6-8 focused hours, max 10; deadline 10/22 | Plan for ~8 focused hours; scope accordingly | Accepted |
| A-04 | Brownfield text "(a new notification channel/ deduplication Refactor provider-specific logic)" parses several ways | Brownfield = event-level deduplication: same `eventId` resubmitted under a new idempotency key is suppressed and reflected in status and audit. Real multi-layer change (schema, intake, status, audit, metrics). Changed after design review; engineer decision | Accepted |

## Submission (4.1)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-10 | "Notification identifier": client- or server-generated? | Server generates a UUID. Client supplies an idempotency key and event id | Accepted |
| A-11 | Where does the idempotency key come from? | `Idempotency-Key` header, **required**, scoped per source system. Missing -> 400 / 4001. No derived fallback (it could suppress a legitimate second notification). Changed by engineer (#2) | Accepted |
| A-12 | Same idempotency key, different payload | Reject with 409 Conflict; same key + same payload returns the original notification | Accepted |
| A-13 | Spec lists no message content field, yet 4.9 mentions sensitive content | Add `subject` + `body`. Stored on the notification only; never in audit or logs | Accepted |
| A-14 | What is a recipient: user id or raw address? | Recipient = user id. Contact addresses and preferences come from a seeded recipient directory. Keeps PII out of requests and audit | Accepted |
| A-15 | Severity vs priority | Both required (spec 4.1), no defaults. Severity drives routing; priority drives worker claim order under backlog. Engineer decision (#2) | Accepted |
| A-16 | Schedule time in the past / expiration in the past | Past schedule = send now. Past expiration = reject 422 | Accepted |
| A-17 | Who are the API callers, how are they authenticated? | API key per source system (from config). `sourceSystem` is taken from the key, not trusted from the body  (ADR-0001) | Accepted |
| A-18 | Spec 4.1 lists one "notification identifier" for a request with 1..n recipients | The spec's "notification" is our submission (one request). Identifiers are per recipient by design: one `notificationId` each; the shared `submissionId` stays internal (#8, ADR-0002) | Accepted |

## Status (4.2)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-20 | What does "delivered" mean? | Two-phase. Vendor 200 = `SENT`; final `DELIVERED` / `BOUNCED` / `FAILED` arrives by vendor callback. No callback = stays `SENT`. Overrides the draft ("provider accepted = delivered"); engineer decision (#4, ADR-0003). `BOUNCED` is stored as `FAILED` with reason `BOUNCED` (simplified after review) | Accepted |
| A-21 | Overall status when some deliveries succeed and some fail | `overallStatus` derived on read, never stored; separate vocabulary from delivery `status` (see architecture.md). Mixed = `PARTIALLY_DELIVERED` (#2) | Accepted |
| A-22 | Does "delivered" mean all routed channels or recipient reached on any channel? | All routed channels; a failed escalated SMS stays visible at the top level (#2) | Accepted |
| A-23 | Must audit history be exposed via API? | Not required (4.9 says record). Exposed anyway as `GET /notifications/{id}/audit`; engineer decision (#2) | Accepted |
| A-24 | Overall status of a notification with no deliveries but a future `scheduledAt` | `NOT_ROUTED`, not `SCHEDULED`: nothing will ever be sent. `SCHEDULED` also requires every delivery still `PENDING` (#11) | Proposed |

## Routing (4.3)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-25 | "Requested or eligible channels": how are channels tied to recipients? | Each recipient object carries optional `channels`; absent = recipient's enabled channels. Policy may add or remove; every change audited with a reason. Engineer decision (#2) | Accepted |
| A-26 | "Configurable channels": configured where? | Routing policy in `application.yml`; preferences in the seeded directory. No admin API | Accepted |
| A-27 | Which channels exist? | EMAIL and SMS (simulated providers) | Accepted |

## Dedup and idempotency (4.4)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-35 | Dedup boundary | Submission: one outcome per (source system, idempotency key). A request with N recipients becomes N notifications sharing an internal `submissionId`. Delivery: one per (notification, channel), unique constraint. Callbacks: one per (provider, event key) (#2, ADR-0002) | Accepted |
| A-38 | Recipients per request; unknown recipient | Max 100. Any unknown recipient rejects the whole request (422 / 4223), all listed. Atomic: one key = one outcome (#2) | Accepted |
| A-36 | Retention | Idempotency keys honored for 7 days (configurable); after that a key may create a new notification | Accepted |
| A-37 | Exactly-once delivery? | Not achievable with external providers. At-least-once with a per-delivery provider idempotency key; documented as a limitation | Accepted |

## Retry and failures (4.5)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-40 | Retry bounds | Max attempts configurable (default 3), exponential backoff from 500ms with equal jitter (waits always grow), never past expiration (engineer decision, #10) | Accepted |
| A-41 | Rate limit counts toward attempt budget? | Yes, still bounded. Honor provider `Retry-After` when given, capped at 60s (engineer decision, #10) | Accepted |
| A-42 | Timeout: provider may or may not have sent it | Retry with the same provider idempotency key so a well-behaved provider dedupes | Accepted |
| A-43 | Auth error: retry or not? | Not retryable for the delivery (config problem, not delivery problem). Logged at error level and counted in metrics | Accepted |
| A-44 | Permanent rejection vs invalid recipient | Both terminal, recorded with distinct reason codes | Accepted |
| A-45 | Retry after vendor acceptance? | Never. Retries cover only failed send calls; after a vendor 200 the vendor owns retries; later FAILED/BOUNCED is final (#4) | Accepted |
| A-46 | Webhook authentication | Shared secret token per provider in `X-Webhook-Token`; vendor signature verification is the production path. Engineer chose this over HMAC to keep the prototype simple (#4) | Accepted |
| A-47 | Callback for a delivery not yet sent or between retries (`PENDING`, `RETRY_SCHEDULED`), e.g. a vendor that accepted a send we classified as `TIMEOUT` | Not applied; audited `CALLBACK_IGNORED` (`while RETRY_SCHEDULED; event ...`). ADR-0003 applies callbacks to `SENDING`/`SENT` only; the retry resends with the same `deliveryId`, which the vendor dedupes (#11) | Proposed |
| A-48 | Late callback for a delivery that expired after a lease reclaim (it may have been sent) | Stays `EXPIRED` (final states never change); audited `CALLBACK_IGNORED` with `late after expiry; event ...`, so the vendor's outcome stays in the history (#11, from the #10 review) | Proposed |
| A-49 | Failure reason for a vendor `FAILED` callback | `CALLBACK_FAILED` (bounce: `BOUNCED`). The vendor's `reasonCode` is restricted to code characters and audited, not stored on the delivery (#11) | Proposed |

## Ambiguous scenario candidate

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-30 | A CRITICAL alert targets a recipient who opted out of the channel the policy wants to use. Which wins? | Worked as scenario 3: options, questions, chosen policy, tests | Open (scenario 3) |

## Questions for the hiring team

1. Sections 4.6-4.8 appear to be missing. Are there requirements there, or is it a numbering gap? (A-01)
2. Section 3.3 has no content. Is the ambiguous scenario ours to choose? (A-02)
