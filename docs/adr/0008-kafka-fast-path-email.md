# ADR-0008: Kafka fast path for EMAIL first dispatch

- Status: Accepted (2026-10-22)
- Issue: #47. Impact analysis: `docs/scenarios/brownfield-2.md`
- Related: ADR-0004 (partially superseded), ADR-0007, SC-2

## Context

Every delivery waits for the next poll (ADR-0004): one thread, 500ms interval. ADR-0004 named a broker as the production path. EMAIL is the high-volume channel.

## Options

| Option | Outcome |
|---|---|
| Full transactional outbox (outbox table, relay, poller retired) | Rejected: more code and poll-query changes; its guarantee (no lost publish) is not needed while the poller stays as reconciliation |
| No Kafka code, design note only | Rejected by engineer: the fast path is worth showing working, off by default |
| Kafka for all channels, retries included | Rejected: Kafka has no delayed delivery (retry backoff, `Retry-After`, lease reclaim, scheduled sends would need retry topics); SMS volume is low |
| **Kafka for EMAIL first dispatch; database stays the source of truth; poller reconciles after a grace window** | **Decided** (engineer, #47) |

## Decision

- Off by default: `nms.kafka.enabled=false`; every Kafka bean is conditional on it. Off, the app, poll queries and tests are unchanged; Boot's Kafka auto-configuration stays inert (no connection).
- Publish: `ChannelRouter` publishes `DeliveriesQueued` (ids and channels) inside the submit transaction; an `AFTER_COMMIT` listener sends each EMAIL delivery id to `nms.delivery.email`, key and value = the id (no content, no addresses). A failure is logged by id, counted (`nms.kafka.published{outcome=failed}`), and never fails the request. A send that fails at once (no metadata within `max.block.ms`, 1s) stops the rest of that submission's sends.
- Consume: `@KafkaListener` calls `DeliveryWorker.process(id)`: skip unless `PENDING` and due, then the poll's per-row path (checks, rate limit, conditional claim on version, send, outcome). No new dedup: a duplicate finds the row no longer `PENDING`; a race with the poller is decided by the claim. Any error is logged; the poller is the retry.
- Poller grace: with Kafka on, the `PENDING` queries skip EMAIL rows due within `nms.kafka.poller-grace` (30s): `findDueOutsideGrace` = `findDue` plus `(channel <> EMAIL or next_attempt_at <= now - grace)`, same index range and order. `RETRY_SCHEDULED`, lease reclaim and SMS unchanged.
- Consumer group `nms-delivery`, `auto-offset-reset: earliest`. One topic; no ordering needed.

## Consequences

- Plus: EMAIL first dispatch no longer waits for a poll; no schema change; the poller still guarantees delivery; duplicates are safe by the existing claim.
- Minus: not an outbox: a lost publish (crash after commit, broker down) waits for the grace window. EMAIL first dispatch not due at publish time (scheduled send) or deferred by the rate limit goes out up to one grace late (poller). With the broker down a submission waits up to `max.block.ms` once on the request thread. The poller still scans EMAIL rows (it skips in-grace ones; filtered rows are read, bounded by EMAIL arrivals not yet sent within one grace).
- Production path: an outbox if lost publishes must not wait the grace; publish off the request thread; retry topics if retries should leave the poller.
