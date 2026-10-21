# ADR-0003: Two-phase delivery status via provider callbacks

- Status: Accepted (2026-10-20)
- Issue: #4
- Related: A-20, A-45, A-46

## Context

Email and SMS vendors accept a message synchronously, then report the real outcome later (delivered, bounced, failed) by calling back with their message id. The first draft treated vendor acceptance as delivered.

## Decision

- A vendor `200` moves a delivery to `SENT` and stores the vendor's message id.
- Final status arrives on `POST /api/v1/webhooks/{provider}`: `DELIVERED`, `BOUNCED` (email), or `FAILED`.
- Each provider adapter maps its vendor's events to these states. Engagement events (open, click) are ignored; `deferred` is audit-only.
- No callback: the delivery stays `SENT` indefinitely. No sweeper invents an outcome.
- Retries cover only failed send calls (timeout, 5xx, 429). After a vendor `200` nothing is resent; the vendor owns its retries.
- Callbacks are at-least-once and may be out of order. Deduped on (provider, event key); final states never change.
- Callbacks for an unknown message id are stored unmatched and applied when the `SENT` write commits (covers the callback-before-commit race). Response is `200` so vendors do not retry.
- Webhook auth: shared secret token per provider in `X-Webhook-Token`.

## Discussion

- Engineer described real vendor behavior: 200 = accepted, delivered/bounce/error arrive by callback. Replaced the draft's single-phase assumption (A-20).
- Engineer confirmed `SENT` stays `SENT` without a callback.
- Engineer challenged HMAC-signed webhooks as unnecessary complexity for the prototype. Replaced with a shared token, which also keeps the curl demo simple. Vendor signature verification (HMAC/ECDSA) is the production path inside each adapter.

Full thread: #4.

## Consequences

- Plus: statuses match how vendors behave; no duplicate sends after acceptance; vendor-specific logic stays in adapters.
- Minus: an extra inbound endpoint; `SENT` can be a resting state; shared token is weaker than vendor signatures.
