# Ambiguity Log

Gaps and unclear points in the spec, with the assumption taken. `Proposed` = drafted, awaiting engineer sign-off. `Accepted` = signed off. `Asked` = sent to the hiring team.

## Spec-level

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-01 | Sections 4.6-4.8 are missing (numbering jumps 4.5 -> 4.9) | Do not invent requirements. Ask the hiring team; proceed with what is written | Asked |
| A-02 | Section 3.3 (ambiguous scenario) has no content | Choose a realistic ambiguous requirement ourselves (see A-30) | Asked |
| A-03 | PDF says 2-3 days; email says 6-8 focused hours, max 10; deadline 10/22 | Plan for ~8 focused hours; scope accordingly | Proposed |
| A-04 | Brownfield text "(a new notification channel/ deduplication Refactor provider-specific logic)" parses several ways | Brownfield = add a new channel + refactor provider-specific logic behind one interface. Dedup is already required by 4.4, so it is greenfield | Proposed |

## Submission (4.1)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-10 | "Notification identifier": client- or server-generated? | Server generates a UUID. Client supplies an idempotency key and event id | Proposed |
| A-11 | Where does the idempotency key come from? | `Idempotency-Key` header, scoped per source system. If absent, derive from `sourceSystem + eventId + type` | Proposed |
| A-12 | Same idempotency key, different payload | Reject with 409 Conflict; same key + same payload returns the original notification | Proposed |
| A-13 | Spec lists no message content field, yet 4.9 mentions sensitive content | Add `subject` + `body`. Stored on the notification only; never in audit or logs | Proposed |
| A-14 | What is a recipient: user id or raw address? | Recipient = user id. Contact addresses and preferences come from a seeded recipient directory. Keeps PII out of requests and audit | Proposed |
| A-15 | Severity vs priority | Severity = business impact, drives routing. Priority = processing order among pending deliveries | Proposed |
| A-16 | Schedule time in the past / expiration in the past | Past schedule = send now. Past expiration = reject 422 | Proposed |
| A-17 | Who are the API callers, how are they authenticated? | API key per source system (from config). `sourceSystem` is taken from the key, not trusted from the body | Proposed |

## Status (4.2)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-20 | What does "delivered" mean? | Provider accepted the message. End-user read receipts are out of scope | Proposed |
| A-21 | Overall status when some deliveries succeed and some fail | Overall status is derived from deliveries, never stored separately. Mixed outcome = `PARTIALLY_DELIVERED` | Proposed |

## Routing (4.3)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-25 | "Requested or eligible channels": hard requirement or hint? | Requested channels are candidates. Policy may add (severity escalation) or remove (opt-out, unsupported channel). Every add/remove is audited with a reason | Proposed |
| A-26 | "Configurable channels": configured where? | Routing policy in `application.yml`; preferences in the seeded directory. No admin API | Proposed |
| A-27 | Which channels exist? | Greenfield: EMAIL, SMS (simulated providers). Brownfield adds one more | Proposed |

## Dedup and idempotency (4.4)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-35 | Dedup boundary | Submission level: one logical notification per (source system, idempotency key). Delivery level: one delivery per (notification, recipient, channel), enforced by a unique constraint | Proposed |
| A-36 | Retention | Idempotency keys honored for 7 days (configurable); after that a key may create a new notification | Proposed |
| A-37 | Exactly-once delivery? | Not achievable with external providers. At-least-once with a per-delivery provider idempotency key; documented as a limitation | Proposed |

## Retry and failures (4.5)

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-40 | Retry bounds | Max attempts configurable (default 4), exponential backoff with jitter, never past expiration | Proposed |
| A-41 | Rate limit counts toward attempt budget? | Yes, still bounded. Honor provider `Retry-After` when given | Proposed |
| A-42 | Timeout: provider may or may not have sent it | Retry with the same provider idempotency key so a well-behaved provider dedupes | Proposed |
| A-43 | Auth error: retry or not? | Not retryable for the delivery (config problem, not delivery problem). Logged at error level and counted in metrics | Proposed |
| A-44 | Permanent rejection vs invalid recipient | Both terminal, recorded with distinct reason codes | Proposed |

## Ambiguous scenario candidate

| ID | Ambiguity | Assumption / decision | Status |
|---|---|---|---|
| A-30 | A CRITICAL alert targets a recipient who opted out of the channel the policy wants to use. Which wins? | Worked as scenario 3: options, questions, chosen policy, tests | Proposed |

## Questions for the hiring team

1. Sections 4.6-4.8 appear to be missing. Are there requirements there, or is it a numbering gap? (A-01)
2. Section 3.3 has no content. Is the ambiguous scenario ours to choose? (A-02)
