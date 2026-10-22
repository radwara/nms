# Brownfield 2: outbound rate limit (impact analysis)

- Issue: #46. Requirements: SC-2, FR-5.2 (rate-limit response, spec 4.5), NFR-4. Related: A-41, #47.
- Baseline: `main` at `e43ed23` (#45 merged), treated as the existing system.
- Status: implemented on `feat/46-rate-limit`; review fixes in section 9; Execution and Validation below.

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
