# ADR-0004: Database-backed delivery queue with a polling worker

- Status: Proposed (2026-10-20)
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
- A row is claimed with a conditional update (`status = SENDING` where id and `version` match). Only one worker wins.
- Scheduling is `nextAttemptAt = scheduledAt`; a retry is `nextAttemptAt = now + backoff`.
- A row stuck in `SENDING` past a lease is reclaimable after a crash; the resend reuses the same provider idempotency key.

## Consequences

- Plus: durable, restart-safe, no extra infrastructure, retries and scheduling come from one column.
- Minus: polling latency (sub-second poll interval) and database load at high volume.
- Production path: PostgreSQL with `SELECT ... FOR UPDATE SKIP LOCKED`, or a broker fed from the table as an outbox.
