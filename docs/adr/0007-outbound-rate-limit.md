# ADR-0007: Outbound rate limit per source system and channel

- Status: Accepted (2026-10-22)
- Issue: #46. Impact analysis: `docs/scenarios/brownfield-2.md`
- Related: ADR-0004, A-41

## Context

Nothing stops one source system from flooding a channel. NMS only reacts to a vendor 429 (`Retry-After`, A-41), and those retries spend the delivery's attempts. One noisy source can use the vendor quota and delay every other source.

## Options

| Option | Outcome |
|---|---|
| Inbound 429 at `POST /notifications` | Rejected: pushes retry logic onto every source; a 100-recipient request is all-or-nothing; the accepted backlog is still unbounded per channel |
| Key = channel only | Rejected: no fairness; the noisy source still starves the others |
| Token bucket | Rejected: smoother, but more state and settings; fixed window is enough at these limits (engineer: simplest possible) |
| Shared store (Redis / database counters) | Rejected: new infrastructure or a write per send; one instance today (ADR-0004 scaling path) |
| CRITICAL bypass | Rejected by engineer: keep it simple; a source can mark everything CRITICAL |
| **Fixed window, in memory, key (source system, channel), in the worker before the claim** | **Decided** (engineer, #46) |

## Decision

- `DeliveryWorker.prepareAndClaim` asks `RateLimiter.acquire(sourceSystem, channel, now)` after every pre-send check, just before the claim. Every attempt counts (retries, lease reclaims); CRITICAL has no bypass.
- `FixedWindowRateLimiter`: windows aligned to multiples of `nms.rate-limit.window` since the epoch; one counter per key in a `ConcurrentHashMap`, updated by one atomic `compute` (safe for concurrent callers).
- Over the limit: the existing conditional `postpone` moves `next_attempt_at` to the window end. Status and attempts unchanged; no audit (not a state change); DEBUG log; `nms.delivery.deferred{channel, reason=rate_limited}`. `expiresAt` stays the only deadline.
- Limits per source per window, from `application.yml`: EMAIL 600, SMS 60, `default-per-source` 30, window 1m. Validated positive at startup.
- Vendor quota stays reactive (429 -> `Retry-After`, A-41).

## Consequences

- Plus: one source cannot use a channel's whole throughput; others keep sending; no schema change, no new dependency; a throttled delivery keeps its attempts.
- Minus: counts are per instance and reset on restart (N instances allow N x limit); up to 2 x limit in a burst across a window boundary; under a flood each poll defers the throttled source's due rows, one update each, and they all become due again at the window end; deferral is visible only in the metric and DEBUG log.
- Production path: a shared counter (Redis, or a database row per key and window) once there is more than one instance; a token bucket if bursts at the boundary matter.
