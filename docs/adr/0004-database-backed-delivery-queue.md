# ADR-0004: Database-backed delivery queue with a polling worker

- Status: Accepted (2026-10-20)
- Related: SC-1, FR-4.2, NFR-2, NFR-4

## Context

Delivery must be asynchronous, survive restarts, support scheduled sends and delayed retries, and never send the same delivery twice from two workers. The prototype must run with no infrastructure beyond the JVM.

## Options

| Option | Durable | Delayed retry / schedule | Extra infrastructure |
|---|---|---|---|
| In-memory queue (`@Async`, `BlockingQueue`) | No | Needs a separate scheduler | None |
| Message broker (Kafka, RabbitMQ, SQS) | Yes | Needs delay queues or retry topics | Broker |
| **Database table polled by a worker** | Yes | Free: `nextAttemptAt` column | None |

## Decision

The `delivery` table is the queue.

- A scheduled worker polls for due rows (`PENDING` or `RETRY_SCHEDULED`, `nextAttemptAt <= now`), ordered by priority then due time, in small batches.
- One single-threaded poller. A row is claimed with a conditional update (`status = SENDING`, `claimed_at = now`), committed before the send. Only one claimer wins.
- Claim and outcome writes are conditional on `id`, expected `status`, and `version`; callback writes only on `id` and `status in (SENDING, SENT)`. Every write increments `version` and sets `updated_at`. A slow worker whose lease was reclaimed cannot overwrite newer state: the reclaim bumped `version`.
- `claimed_at` is used only for the lease reclaim range check (`claimed_at < now - lease`).
- Persisted timestamps come from an injected `Clock` truncated to microseconds (database precision).
- Scheduling is `nextAttemptAt = scheduledAt`; a retry is `nextAttemptAt = now + backoff`.
- The lease is longer than the provider timeout. A row stuck in `SENDING` past the lease is reclaimable after a crash; the resend reuses the same provider idempotency key (`deliveryId`).
- Priority is copied onto the delivery as a number so the poll sorts correctly with an index on `(status, priority, next_attempt_at)`.

## Revision (2026-10-21, #13)

The decision stands; three details changed during implementation (architecture.md is current):

- Callback writes are conditional on `id`, `provider`, and `status in (SENDING, SENT, RETRY_SCHEDULED)` (#11, A-47), not `id` and `status in (SENDING, SENT)`.
- The poll is one bounded query per (status, priority), merged in Java by priority then due time (#10 performance review), not one ordered query.
- Due time is `next_attempt_at = max(now, scheduledAt)` (#9): a past `scheduledAt` is sent now (A-16).

## Consequences

- Plus: durable, restart-safe, no extra infrastructure, retries and scheduling come from one column.
- Minus: polling latency (sub-second poll interval) and database load at high volume.
- Production path: PostgreSQL with `SELECT ... FOR UPDATE SKIP LOCKED`, or a broker fed from the table as an outbox.
