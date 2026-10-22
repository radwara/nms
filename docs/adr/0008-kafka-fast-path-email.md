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
- Publish: `ChannelRouter` publishes `DeliveriesQueued` (ids and channels) inside the submit transaction; an `AFTER_COMMIT` listener hands the submission's EMAIL ids to one publish thread (bounded queue, 1,000 submissions) and returns: the request never waits for Kafka while holding its JDBC connection (review: before, a broker outage blocked each submit for `max.block.ms` holding a connection, so 10 concurrent submits drained the pool). The publish thread sends each id to `nms.delivery.email`, key and value = the id (no content, no addresses). A failure or a full queue is logged by id, counted (`nms.kafka.published{outcome=failed}`), and left to the poller. A send that fails at once (no metadata within `max.block.ms`, 1s) stops the rest of that submission's sends. The producer is warmed (topic metadata fetched) on the publish thread at startup.
- Consume: `@KafkaListener` calls `DeliveryWorker.process(id)`: skip unless `PENDING` and due, then the poll's per-row path (checks, rate limit, conditional claim on version, send, outcome). No new dedup: a duplicate finds the row no longer `PENDING`; a race with the poller is decided by the claim. Any error is logged; the poller is the retry.
- Poller grace: with Kafka on, the `PENDING` queries skip EMAIL rows created within `nms.kafka.poller-grace` (30s) and never moved: `findDueOutsideGrace` = `findDue` plus `(channel <> EMAIL or created_at <= now - grace or version > 0)`, same index range and order. Postpone and rate-limit deferral bump the version, so a moved row goes to the poller when due; a send scheduled beyond the grace goes at its due time (engineer, review). `RETRY_SCHEDULED`, lease reclaim and SMS unchanged.
- Topic declared with a `NewTopic` (`nms.kafka.partitions`, 3); listener concurrency `nms.kafka.concurrency` (3). Consumer group `nms-delivery`, `auto-offset-reset: earliest`. No ordering needed across messages.

## Consequences

- Plus: EMAIL first dispatch no longer waits for a poll; no schema change; the poller still guarantees delivery; duplicates are safe by the existing claim.
- Minus: not an outbox: a lost publish (crash after commit, broker down, full queue, queued at shutdown) waits for the grace window. The consumer works in topic order, not priority: under consumer lag a CRITICAL EMAIL waits behind earlier messages; worst case the poller takes it after the grace, by priority (engineer: documented, not fixed). A send scheduled less than one grace after submission goes out at creation + grace. The poller still scans EMAIL rows (it skips in-grace ones; filtered rows are read, bounded by EMAIL arrivals not yet sent within one grace).
- Production path: partitions and listener concurrency are the scaling knobs (more partitions than consumer threads across instances); consumer lag (`records-lag-max`, exposed through Micrometer by the Kafka client metrics) is the alert, with the grace as its threshold; an outbox if lost publishes must not wait the grace; retry topics if retries should leave the poller; a priority topic if CRITICAL must not queue behind bulk EMAIL.
