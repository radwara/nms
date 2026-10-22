# Brownfield 2: outbound rate limit (impact analysis)

- Issue: #46. Requirements: SC-2, FR-5.2 (rate-limit response, spec 4.5), NFR-4. Related: A-41, #47.
- Baseline: `main` at `e43ed23` (#45 merged), treated as the existing system.
- Status: merged in #49 (`950dcfd`); review fixes in section 9; Execution and Validation below.

## 1. Context and gap

Nothing stops one source system from flooding a channel. NMS only reacts to a provider 429 (`RATE_LIMITED`, `Retry-After`, A-41), and those retries spend the delivery's attempts. One noisy source can use the whole vendor quota and push other sources' messages behind its backlog.

## 2. Engineer decisions (#46, 2026-10-22)

| Decision | Source |
|---|---|
| Fixed window, in-memory counters, limits from `application.yml` | Engineer |
| Outbound, in the delivery worker; key = (source system, channel). Vendor quota stays reactive (429 -> `Retry-After`, A-41) | Engineer |
| No bypass for CRITICAL | Engineer |
| Per source per minute: EMAIL 600, SMS 60; unlisted channel: explicit `default-per-source` 30. Demo stays under the limits | Engineer |

## 3. Design proposal

- Check in `DeliveryWorker.prepareAndClaim` after every pre-send check (missing rows, expiry, attempts, recipient, adapter, address) and just before the claim. Every attempt counts, retries and lease reclaims included.
- Window aligned to multiples of `window` since the epoch (UTC). One counter per (source system, channel), replaced when its window passes.
- Allowed: counted, then claimed. A lost claim (another writer won the row) still uses one unit; negligible on one instance. (Superseded by R3: the unit is released.)
- Over the limit: `deliveries.postpone(id, status, version, windowEnd, now)` (conditional, existing query). (Superseded by R1: one set-based postpone per key.) Status and `attempt_count` unchanged; not counted as worked; no audit (not a state change); DEBUG log; metric. A deferred row past `expiresAt` expires on its next poll through the existing path, before the check.
- Seam first: package-private `RateLimiter` interface, allow-all in the refactor commit; `FixedWindowRateLimiter` in the feature.
- Added after this analysis (#47: a Kafka consumer thread will call the same per-row path, `prepareAndClaim`, alongside the poller): the limiter is safe for concurrent callers, one atomic `ConcurrentHashMap.compute` per key; a caller with an older `now` counts in the key's current window.

## 4. Affected code (at `e43ed23`)

| File:line | Change |
|---|---|
| `delivery/DeliveryWorker.java:80-99` | Constructor gains a `RateLimiter`; the existing 9-arg constructor is kept (allow-all) because `DeliveryWorkerTest` calls it |
| `delivery/DeliveryWorker.java:253` | Limiter check before `deliveries.claim` |
| `delivery/DeliveryWorker.java:301-313` | Existing `postpone` (ERROR log, backoff) unchanged; new quiet `defer` path beside it |
| `delivery/DeliveryRepository.java:167-181` | None: `postpone` reused as is |
| `delivery/DeliveryMetrics.java:35-52` | New `deferred(channel, reason)` counter |
| `delivery/WorkerConfiguration.java:14,23` | Limiter bean; `RateLimitProperties` enabled |
| New `delivery/RateLimiter.java`, `FixedWindowRateLimiter.java`, `RateLimitProperties.java` | Seam, limiter, settings |
| `resources/application.yml:60-73` | New `nms.rate-limit` block |

## 5. Config, metric, data

```yaml
nms:
  rate-limit:
    window: 1m             # positive; windows aligned to multiples of it
    per-source:            # sends per source system, channel, window; positive
      EMAIL: 600
      SMS: 60
    default-per-source: 30 # any channel not listed; positive
```

- Startup fails on a zero or negative window or limit, or an unknown channel key. Read at startup; a change takes effect on restart.
- Metric: `nms.delivery.deferred{channel, reason=rate_limited}`, one per deferral written. Tags bounded (no source system tag; the DEBUG log carries it).
- Data: **no schema change.** `postpone` writes only `next_attempt_at`, `version`, `updated_at`. Counters live in memory only.

## 6. Greenfield tests that must change

| Test | Change | Reason |
|---|---|---|
| `DeliveryPollerIT` (class) | Context property `nms.rate-limit.per-source.EMAIL=10000`; assertions unchanged | `backlogDrainsWithoutWaitingAPollIntervalPerBatch` sends 1,000 EMAIL from one source (`billing`) in under 10 s on the real clock, above the 600/min default by design; it measures drain speed, not limits. Both tests share one context (one limiter), 1,001 sends. Separate commit before the feature (the property is unbound until then). First set to 1000, which failed `scheduledPollerSendsDueDeliveries` (the 1,001st send); corrected before the feature commit |

Unchanged, checked:
- `DeliveryWorkerTest`: 9-arg constructor kept, limiter allow-all.
- Every other test: a temporary probe on the baseline (uncommitted; counted sends per context, source, channel and aligned minute over the full `verify`, 280 ITs) found no key above 30 except `DeliveryPollerIT` (`billing` / EMAIL past 600). Contexts share one limiter across test classes, so this is the number that matters.
- `DeliveryMetricsIT` asserts named series only; the new counter registers on first deferral.
- `scripts/demo.sh`: one run sends at most 10 EMAIL and 8 SMS from `demo-app` (retry step 3 + 3); limits 600 / 60.

## 7. Test plan

| Test | Asserts |
|---|---|
| Refactor commit | Full suite green, zero `src/test` diff |
| `FixedWindowRateLimiterTest` | Under the limit allowed; at the limit deferred to the window end; next window resets; keys independent (source, channel); unlisted channel uses `default-per-source`; concurrent callers never exceed the limit |
| `RateLimitPropertiesTest` | Defaults bind; zero / negative window, limit, default fail startup |
| `RateLimitIT` (low limit, `MutableClock`) | Same source: one `SENT`, one still queued, `attempt_count` 0, due at window end, no audit; another source sends; after the window the deferred one sends; retries count; over-deferred row past `expiresAt` expires; `nms.delivery.deferred` counted |

## 8. Limitations

- Single instance: counts are per instance and reset on restart (N instances allow N x limit).
- Fixed window: up to 2 x limit in a short burst across a window boundary.
- Under a flood, each poll defers the throttled source's due rows one conditional update each, and at the window end they all become due again. Other sources still send: deferred rows leave the due set.
- Not visible in status or audit (by decision); only the metric and DEBUG log.
- Vendor-wide quota (all sources together) is still reactive (A-41).
- Review additions (section 9): with several instances each counts its own units, so the total can reach N x limit; the deferred backlog of a key is re-swept once per window by one UPDATE (all its rows become due together at the window end); spreading the backlog over future windows is a follow-up, not built.

## 9. Review fixes (#46 review: bug-finder, perf check)

Perf check measured the blocker: 5,000 due rows, limit 600, batch 20. Each denial postponed one row, so a window took about 250 polls and 4,400 single-row postpones, and the other source's row went out on the last poll of every window.

| # | Finding | Fix |
|---|---|---|
| R1 | Other sources wait behind the throttled backlog (blocker) | First denial of a key in a poll: one set-based `DeliveryRepository.postponeDue(source, channel, now, claimedBefore, windowEnd)` (join `notification` for `source_system`; `PENDING` / `RETRY_SCHEDULED` due, or `SENDING` past the lease, the poll's own conditions; `version + 1`, so an in-flight claim or outcome write on those rows changes nothing). The key is then skipped for the rest of the batch, no more writes. `nms.delivery.deferred` counts rows moved |
| R2 | No FIFO among rows postponed to the same instant | Tiebreak `created_at` after `next_attempt_at` in `findDue` and `BEST_FIRST`. Every existing test passes unchanged. H2 plan unchanged (5,000 due rows: 5,001 read with either order; H2 already sorts after the index range). PostgreSQL: the extra sort column defeats the index-ordered stop-after-limit scan; production path is `created_at` in `ix_delivery_poll` (schema change, not done) |
| R3 | A lost or throwing claim used a unit | `RateLimiter.release(key, windowStart)`; `acquire` returns `Allowed(windowStart)` or `Denied(until)`. A release for a passed window changes nothing |
| R4 | Clock set back kept the old window | `now` more than one window before the key's window starts a new window; smaller skew still counts in the current window (concurrent callers) |
| R5 | Java defaults differed from `application.yml` | `per-source` not configured at all: EMAIL 600, SMS 60; `default-per-source` 30. When configured, the channels listed |

Not done (follow-up): spread a large deferred backlog over future windows instead of re-sweeping it once per window.

## Execution

Branch `feat/46-rate-limit` from `e43ed23`.

| # | Commit | Content |
|---|---|---|
| 1 | `b12d368` docs(delivery): rate limit impact analysis | This file, before any code |
| 2 | `d1d240e` refactor(delivery): rate limit seam, allow-all | `RateLimiter` (`ALLOW_ALL` bean), check and quiet `defer` before the claim; 9-arg constructor kept. `./mvnw -B verify` green: 317 unit, 280 IT |
| 3 | `124b8cd` test(delivery): poller backlog IT raises the EMAIL rate limit | The one planned test change (section 6) |
| 4 | `9cb3786` feat(delivery): outbound rate limit per source and channel | `RateLimitProperties`, `FixedWindowRateLimiter`, `nms.delivery.deferred`, `application.yml`, tests |
| 5 | `763703e` feat(delivery): rate limiter safe for concurrent callers | `ConcurrentHashMap.compute`; concurrency test |
| 6+ | docs | ADR-0007, `architecture.md`, README, `requirements.md`, this section |
| R | `c44ef1b` fix(delivery): fair rate limit deferral, FIFO order, slot release | Review fixes R1-R5 (section 9) and their tests |
| R | `96c4d7e` test(delivery): fix row order in the rate limit release test | New test tied on due time and fell back to random id; distinct due times |

Zero test changes in the refactor commit:

```
$ git diff --stat b12d368..d1d240e -- src/test
(empty)
```

Greenfield tests changed: `DeliveryPollerIT` (one property, section 6). No assertion changed or removed. The review fixes (incl. the `findDue` order) changed no greenfield test.

## Validation

| Test | Covers |
|---|---|
| `FixedWindowRateLimiterTest` | Under the limit; at the limit deferred to the window end; deferred sends not counted; window reset; keys independent; unlisted channel default; alignment (10s window); older `now` counts in the current window; 8 threads x 50 acquires allow exactly the limit (a non-atomic get/put variant fails it 3 of 3 runs) |
| `RateLimitPropertiesTest` | Binding; record defaults (1m, 30); zero / negative window, channel limit, default fail startup; unknown channel fails |
| `RateLimitDefaultsIT` | Shipped `application.yml`: 1m, EMAIL 600, SMS 60, default 30 |
| `FixedWindowRateLimiterTest` (review) | Release gives a unit back; release for a passed window ignored; clock set back more than one window starts a new window; exactly one window back counts in the current one |
| `DeliveryWorkerRateLimitTest` | A lost claim and a throwing claim release their unit (the next row of the key is sent, no postpone); without a release the second row is deferred |
| `RateLimitFloodIT` (EMAIL limit 5, batch 20, `MutableClock`, Hibernate statement inspector) | 50 due rows of one source, one new row of another per window, 10 windows: the other source is sent on poll 2 (poll 1 in the last window); per window 1 set-based postpone (0 in the last) and 13 `update delivery` statements (5 claims + 5 `SENT` for the flood, 2 for the other source, 1 postpone; 12 in the last); flood rows sent in creation order |
| `RateLimitIT` (EMAIL limit 1, `MutableClock`) | Same source: one `SENT`, one `PENDING`, attempts 0, due at window end, no audit, no log line at INFO or above, metric +1; another source sends; not sent before the window end, sent at it; a retry is deferred (`RETRY_SCHEDULED`, attempts 1); a row deferred past `expiresAt` expires (`EXPIRED before send`, attempts 0); CRITICAL deferred too; the set-based postpone moves only the key's due rows (`PENDING`, `RETRY_SCHEDULED`, `SENDING` past the lease), not a fresh claim, a future, `SENT`, other-source or other-channel row, and a claim with the polled version then fails |

`./mvnw -B verify`: 343 unit, 287 integration, 0 failures (after the review fixes; 336 / 285 before).

Demo (`scripts/demo.sh`, dev-profile jar on port 18080, fresh database): `Demo passed: 9 steps`. Attempts scraped afterwards: EMAIL 10, SMS 8 from `demo-app` (limits 600 / 60); no `nms_delivery_deferred` series.

---

# #47: Kafka fast path for EMAIL dispatch (impact analysis)

- Issue: #47. Requirements: SC-2, NFR scalability. Revisits ADR-0004 (broker named as the production path).
- Baseline: `main` at `950dcfd` (#46 merged), treated as the existing system.
- Status: merged in #53 (`6a968be`); Execution and Validation below.

## 1. Context and gap

Every delivery waits for the next poll (500ms interval, one thread, ADR-0004). The poll is the only dispatch path, so EMAIL latency and throughput are bound to it.

## 2. Engineer decisions (#47, 2026-10-22)

| Decision | Source |
|---|---|
| Kafka is the fast path for EMAIL first dispatch; the database stays the source of truth; the poller stays as reconciliation (not a transactional outbox) | Engineer |
| Grace window: with Kafka on, the poller takes EMAIL first-dispatch rows only once due longer than `nms.kafka.poller-grace` (30s) | Engineer |
| Retries, lease reclaim and SMS stay with the poller (Kafka has no delayed delivery) | Engineer |
| Off by default (`nms.kafka.enabled=false`): app, demo, existing tests need no broker | Engineer |
| Rejected: full outbox; no Kafka code (design note only) | Engineer |

## 3. Design proposal

- Publish: `ChannelRouter` publishes one Spring event per submission with the deliveries it saved (id, channel), inside the submit transaction. A `@TransactionalEventListener(AFTER_COMMIT)` (Kafka on only) sends each EMAIL id to `nms.delivery.email`, key and value = the id. A failed or throwing send is logged by id, counted, and swallowed: the API response is unaffected, the poller sends the row after the grace window. A send that fails at once (broker metadata unavailable after `max.block.ms`) stops the rest of that submission's sends (counted failed), so a broker outage costs a request at most one `max.block.ms`. With no listener (Kafka off) the event does nothing. (Superseded by R1: the request thread hands the ids to a bounded publish thread.)
- Consume: `@KafkaListener` calls a new `DeliveryWorker.process(UUID)`: load the row; skip unless `PENDING` and due (same condition as the poll; a `SENT` row must be skipped because `claim` only checks the read status and version); load its notification and recipient; run the existing per-row `work` (checks, rate limit, conditional claim, send, outcome) under a fresh trace id. No new dedup: a duplicate message finds the row no longer `PENDING`, and a race with the poller is decided by the claim (one winner).
- Poller grace: with Kafka on, the `PENDING` queries exclude EMAIL rows due after `now - grace`; `RETRY_SCHEDULED`, lease reclaim and SMS keep `now`. With Kafka off, `poll()` runs the same `findDue` calls as today. One sibling query (`findDue` plus `and (channel <> :channel or next_attempt_at <= :cutoff)`, same order) is used for `PENDING` when Kafka is on: same index range and stop-after-limit, plus the in-grace EMAIL rows Kafka has not sent yet. Filtering in Java after the existing query was rejected: 20 in-grace EMAIL rows at the front of a priority would hide every newer SMS row behind them until the grace passes (SMS delayed whenever Kafka lags). (Superseded by R2: grace measured from `created_at`, moved rows exempt.)
- Seam first: `DeliveriesQueued` event published by the router, and `process(UUID)` extracted from the poll loop; both unused until the feature.

## 4. Affected code (at `950dcfd`)

| File:line | Change |
|---|---|
| `routing/ChannelRouter.java:90` | After `saveAll`, publish `DeliveriesQueued` (one per submission) (still in the transaction; intake boundary is `IntakeService.java:91`) |
| `delivery/DeliveryWorker.java:156-193` | Per-row trace scope and `work` call extracted, shared by the poll loop and `process(UUID)` |
| `delivery/DeliveryWorker.java:212-223` | `poll()`: `PENDING` uses the grace query when a grace is configured |
| `delivery/DeliveryWorker.java:109-131` | New constructor with the optional grace; 9- and 10-arg constructors kept (`DeliveryWorkerTest`, `DeliveryWorkerRateLimitTest` call them) |
| `delivery/DeliveryRepository.java:35-45` | `findDue` unchanged; sibling grace query added |
| `delivery/DeliveryMetrics.java` | New `nms.kafka.published{outcome}` counter |
| New `delivery/DeliveriesQueued.java`, `KafkaDispatch*` (properties, configuration, publisher, listener) | All Kafka beans conditional on `nms.kafka.enabled=true` |
| `pom.xml` | `spring-boot-starter-kafka`; `spring-boot-starter-kafka-test` (embedded KRaft broker, test scope); versions from the Boot 4.1.1 BOM (spring-kafka 4.1.1, kafka 4.2.1) |
| `resources/application.yml` | `nms.kafka` block; `spring.kafka` producer `max.block.ms`, consumer group and `auto-offset-reset: earliest` |

## 5. Config, metrics, threading, data

```yaml
nms:
  kafka:
    enabled: false      # NMS_KAFKA_ENABLED; broker from SPRING_KAFKA_BOOTSTRAP_SERVERS
    poller-grace: 30s   # positive; EMAIL first dispatch left to Kafka this long
```

- Metrics: `nms.kafka.published{outcome=ok|failed}`. Sends and outcomes reuse `nms.delivery.attempts` and `nms.deliveries.outcome`. Consumer lag comes from the Kafka client metrics (Boot binds them to Micrometer) or the broker.
- Threading: `process` runs on the listener thread alongside the poller thread. `DeliveryWorker` shared state: all fields final and stateless or thread-safe, except `volatile boolean stopping`. Per-poll state (batch, lookup maps, throttled keys) is local to `pollOnce`; `process` uses its own. Collaborators: repositories (each write its own transaction), `DeliveryOutcomes` (stateless, `REQUIRES_NEW`), `RetryPolicy` (immutable; `Jitter` uses `ThreadLocalRandom`), `FixedWindowRateLimiter` (`ConcurrentHashMap.compute`, made safe in #46 for this), `DeliveryMetrics` (Micrometer; the `due` gauge is set by the poller only), `TraceIds` (MDC, thread-local), `SimulatedProvider` (concurrent maps). No new locking needed.
- Data: **no schema change.** Kafka payload is the delivery id only; no content or addresses.
- Kafka off: Boot's Kafka auto-configuration creates inert factories (no connection, no `NewTopic`, no listener container); checked by test.

## 6. Greenfield tests that must change

None. Kafka is off by default and `poll()` runs the same queries when off. `DeliveryWorkerTest` and `DeliveryWorkerRateLimitTest` keep their constructors.

## 7. Test plan

| Test | Asserts |
|---|---|
| Refactor commit | Full suite green, zero `src/test` diff |
| Unit (worker, mocks) | Grace cutoff: Kafka on, `PENDING` queries use EMAIL and `now - grace`; `RETRY_SCHEDULED` and lease reclaim use `now`; Kafka off, only `findDue`. `process`: skips non-`PENDING` and not-yet-due rows without a claim; lost claim, no send |
| Query IT | Grace query: in-grace EMAIL excluded; SMS and past-grace EMAIL returned; FIFO order |
| Embedded broker IT (grace 60s, poller on) | EMAIL intake -> Kafka -> consumer -> `SENT` well inside the grace; same id published twice sends once; SMS sent by the poller, nothing published |
| Publish failure IT (no broker, grace 2s, poller on) | Submit still accepted; `published{failed}`; poller sends the row, not before the grace |
| Kafka off IT | No Kafka listener containers, no publisher bean |

## 8. Limitations

- Not a transactional outbox: a crash between commit and publish, or a failed publish, delays the delivery by the grace window (the poller sends it).
- The poller still scans EMAIL; it only skips in-grace rows.
- EMAIL first dispatch that is not due at publish time (scheduled send) or deferred by the rate limit goes out up to one grace window after it becomes due (poller). (Fixed by R2, except a schedule shorter than the grace: creation + grace.)
- Publish runs on the request thread after commit; with the broker down a submission waits up to `max.block.ms` (1s) once before its sends fail. (Fixed by R1.)
- One topic, no ordering guarantee needed (each message is independent; the claim arbitrates).

## Review fixes (#47 review: three reviews; no double-send or lost delivery found)

| # | Finding | Fix |
|---|---|---|
| R1 | Blocker (perf, measured): the after-commit publish ran on the request thread while it held its JDBC connection; broker down, each EMAIL submit blocked `max.block.ms` with a connection, 10 concurrent submits drained the pool | Publish handed to one daemon thread with a bounded queue (1,000 submissions; full: counted failed, poller). Producer warmed at startup on that thread (first send measured 157ms). `KafkaBrokerDownSubmitIT` (`max.block.ms` 3s, 10 concurrent submits): 3,315ms per submit before, 164ms after, 0 active connections while the publishes still block |
| R2 | Grace measured from due time delayed scheduled and moved rows by a grace | Engineer: `created_at <= now - grace or version > 0` (postpone and deferral bump the version) |
| R3 | `KafkaEmailDispatchIT` did not prove the consumer sent the row (passed with the grace ignored) | Consumer stopped: three `runOnce()` leave the row `PENDING`, attempts 0; consumer started: `SENT` inside the grace. Fails with the grace ignored |
| R4 | Throughput: one consumer thread, topic shape left to the broker | `NewTopic` with `nms.kafka.partitions` (3), listener `nms.kafka.concurrency` (3) |
| R5 | CRITICAL under consumer lag waits in topic order | Engineer: documented (ADR-0008, `architecture.md`), not fixed |
| #51 | Poll index lacked the `created_at` tiebreak; H2 sorted the whole due range | V4 rebuilds `ix_delivery_poll` as `(status, priority, next_attempt_at, created_at)`; the ORDER BY names `status, priority` too (H2 follows an index for the order only from its first column). 5,000 due rows, limit 20: 5,001 read before (also with V4 alone), 20 after |

Follow-up: #52 (collapse the `DeliveryWorker` telescoping constructors, kept for the zero-test-diff refactors).

## Execution (#47)

Branch `feat/47-kafka-email` from `950dcfd`.

| # | Commit | Content |
|---|---|---|
| 1 | `2594582` docs(delivery): Kafka fast path impact analysis | This section, before any code |
| 2 | `64889ef` refactor(delivery): dispatch seam, queued event, process by id | `DeliveriesQueued` published by `ChannelRouter` (no listener); `DeliveryWorker.process(UUID)` sharing the poll's per-row path. `./mvnw -B verify` green: 343 unit, 287 IT |
| 3 | `01ad592` feat(delivery): Kafka fast path for EMAIL first dispatch | Starter + test starter, `KafkaDispatch*`, publisher, listener, grace query, `nms.kafka.published`, tests |
| 4 | `96ce97e` docs | ADR-0008, ADR-0004 status, `architecture.md`, README, `requirements.md`, this section |
| 5 | `3fbde6b` fix(delivery): poll index covers created_at tiebreak (#51) | V4 migration, ORDER BY, `DeliveryQueueIT` scan test on the real ORDER BY (existing one updated, 5,000-row one added), `SchemaMigrationIT` |
| 6 | `eb278d8` fix(delivery): async bounded publish, grace by creation, topic | R1-R4, tests first |
| 7 | docs | Review fixes in ADR-0008, `architecture.md`, this file |

Zero test changes in the refactor commit:

```
$ git diff --stat 2594582..64889ef -- src/test
(empty)
```

Greenfield tests changed: none. Dependency note: `spring-boot-starter-kafka-test` excludes `log4j-core` (it brings an annotation processor that fails `-Werror`; the embedded broker logs through SLF4J).

## Validation (#47)

| Test | Covers |
|---|---|
| `DeliveryWorkerFastPathTest` | Partitions and concurrency positive; grace on: `PENDING` queries get EMAIL and `now - grace`, `RETRY_SCHEDULED` and lease reclaim get `now`; grace off: only `findDue`; cutoff arithmetic; grace must be positive; `process` sends a due `PENDING` row through the claim, skips `SENT`, `RETRY_SCHEDULED`, not-yet-due, missing rows and a stopping worker without a claim, no send on a lost claim |
| `EmailDispatchPublisherTest` | Request thread only hands over the ids; full queue counted failed, no send; warm-up on the publish thread never throws; EMAIL ids only, key = value = id; a send failing at once stops the rest (all counted failed); a throw is swallowed; a later failure counted on completion |
| `DeliveryGraceQueryIT` | EMAIL created within the grace left out; SMS and older EMAIL returned earliest first; in-grace rows do not use up the limit; scheduled beyond the grace: returned at due + 1s; scheduled 1s ahead: not at due + 1s, returned at creation + grace; rate-deferred: returned at its window end; error-postponed: returned when due |
| `KafkaBrokerDownSubmitIT` (no broker, `max.block.ms` 3s) | 10 concurrent submits each under 1s; publishes still pending; 0 active Hikari connections |
| `DeliveryQueueIT`, `SchemaMigrationIT` (#51) | Poll reads 20 of 5,000 due rows with the real ORDER BY; V4 index columns |
| `KafkaEmailDispatchIT` (embedded KRaft broker, one partition, poller on, grace 60s) | Consumer stopped: poller leaves the row `PENDING`; consumer started: `SENT`. Intake -> Kafka -> consumer -> `SENT` well inside the grace, `published{ok}` +1; same id published twice: one attempt, one `SENT` audit; SMS sent by the poller, nothing published |
| `KafkaPublishFailureIT` (no broker, grace 2s, consumer not started) | Submission accepted; `published{failed}` +2 (two recipients); the poller sends both, not before created + grace |
| `KafkaDisabledIT` | Default config: no dispatch beans, no listener containers, no grace |

Mutation checks (not committed): consumer `process` doing nothing fails the fast-path and duplicate tests; poller ignoring the grace fails `KafkaPublishFailureIT` (and the unit and query tests).

`./mvnw -B verify`: 363 unit, 302 integration, 0 failures after the review fixes (359 / 294 before). No Kafka connection attempt in the log of the full run (Kafka off everywhere except the two Kafka ITs). Kafka ITs run 4 times (full verify + 3 reruns), before and after the review fixes: all green.

Demo (`scripts/demo.sh`, dev-profile jar on port 18080, fresh database, Kafka off): `Demo passed: 9 steps` (again after the review fixes and V4).
