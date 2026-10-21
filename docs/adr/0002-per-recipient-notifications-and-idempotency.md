# ADR-0002: Per-recipient notifications and idempotency

- Status: Accepted (2026-10-20)
- Issue: #2
- Related: A-11, A-25, A-35, A-36, A-38

## Context

Spec 4.1 requires a request to accept one or more recipients. Spec 4.4 requires that repeating a submission with the same idempotency key does not create a second logical notification. Callers need to track and query delivery per user.

## Options

| Option | Summary | Trade-off |
|---|---|---|
| A. One notification, N recipients | Single record; status filtered per recipient | Mixed per-user outcomes in one status; needs a join table |
| B. Accept N, persist one per recipient | Split on intake; shared internal `submissionId` | Each user tracked independently; content copied per recipient |

## Decision

**B.**

- `recipients` is a list of `{ userId, channels? }`, max 100. Each entry becomes one `Notification`.
- `Idempotency-Key` header is required, scoped per source system. Stored in `IdempotencyRecord` with a SHA-256 hash of the request body and the `submissionId`.
- Same key, same hash: `200` with the original notification ids, `DUPLICATE_SUBMISSION` audited on each. Same key, different hash: `409 / 4091`.
- The idempotency record and all notifications are written in one transaction.
- Any unknown recipient rejects the whole request (`422 / 4223`). One key always maps to one outcome.
- Keys are honored for 7 days (configurable), then purged; afterwards the key may be reused.
- `submissionId` is internal: used for replay and audit grouping, not returned.

## Discussion

- Engineer asked how channels tie to users when one request has many recipients. First draft added per-recipient overrides to a shared notification.
- Engineer proposed one notification per user for simpler tracking, storage, and processing. Adopted; spec still satisfied because the API accepts many.
- Engineer questioned returning a grouping id the caller cannot use. Made internal.
- Engineer found the request still applied one `channels` list to all recipients. Moved `channels` onto each recipient.

Full thread: #2.

## Consequences

- Plus: per-user status is a single lookup; simple model (`Delivery` is just notification + channel); replay recovers a lost response.
- Minus: content stored once per recipient (bounded by the 100 cap); no API to fetch all notifications of a submission.
