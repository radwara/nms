# Brownfield 2: outbound rate limit (impact analysis)

- Issue: #46. Requirements: SC-2, FR-4.5 as named in the issue (spec 4.5; the rate-limit failure class is FR-5.2 in `requirements.md`), NFR-4. Related: A-41.
- Baseline: `main` at `e43ed23` (#45 merged), treated as the existing system.
- Status: analysis committed first; Execution and Validation filled in after the code.

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
- Allowed: counted, then claimed. A lost claim (another writer won the row) still uses one unit; negligible on one instance.
- Over the limit: `deliveries.postpone(id, status, version, windowEnd, now)` (conditional, existing query). Status and `attempt_count` unchanged; not counted as worked; no audit (not a state change); DEBUG log; metric. A deferred row past `expiresAt` expires on its next poll through the existing path, before the check.
- Seam first: package-private `RateLimiter` interface, allow-all in the refactor commit; `FixedWindowRateLimiter` in the feature.

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
| `DeliveryPollerIT.backlogDrainsWithoutWaitingAPollIntervalPerBatch` | Context property `nms.rate-limit.per-source.EMAIL=1000`; assertions unchanged | Sends 1,000 EMAIL from one source (`billing`) in under 10 s on the real clock: above the 600/min default by design. The test measures drain speed, not limits. Separate commit before the feature (the property is unbound until then) |

Unchanged, checked:
- `DeliveryWorkerTest`: 9-arg constructor kept, limiter allow-all.
- Every other test: a temporary probe on the baseline (uncommitted; counted sends per context, source, channel and aligned minute over the full `verify`, 280 ITs) found no key above 30 except `DeliveryPollerIT` (`billing` / EMAIL past 600). Contexts share one limiter across test classes, so this is the number that matters.
- `DeliveryMetricsIT` asserts named series only; the new counter registers on first deferral.
- `scripts/demo.sh`: one run sends at most 10 EMAIL and 8 SMS from `demo-app` (retry step 3 + 3); limits 600 / 60.

## 7. Test plan

| Test | Asserts |
|---|---|
| Refactor commit | Full suite green, zero `src/test` diff |
| `FixedWindowRateLimiterTest` | Under the limit allowed; at the limit deferred to the window end; next window resets; keys independent (source, channel); unlisted channel uses `default-per-source` |
| `RateLimitPropertiesTest` | Defaults bind; zero / negative window, limit, default fail startup |
| `RateLimitIT` (low limit, `MutableClock`) | Same source: one `SENT`, one still queued, `attempt_count` 0, due at window end, no audit; another source sends; after the window the deferred one sends; retries count; over-deferred row past `expiresAt` expires; `nms.delivery.deferred` counted |

## 8. Limitations

- Single instance: counts are per instance and reset on restart (N instances allow N x limit).
- Fixed window: up to 2 x limit in a short burst across a window boundary.
- Under a flood, each poll defers the throttled source's due rows one conditional update each, and at the window end they all become due again. Other sources still send: deferred rows leave the due set.
- Not visible in status or audit (by decision); only the metric and DEBUG log.
- Vendor-wide quota (all sources together) is still reactive (A-41).

## Execution

_Filled in after the code._

## Validation

_Filled in after the code._
