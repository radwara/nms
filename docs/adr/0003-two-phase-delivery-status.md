# ADR-0003: Two-phase delivery status via provider callbacks

- Status: Accepted (2026-10-20)
- Issue: #4
- Related: A-20, A-45, A-46

## Context

Email and SMS vendors accept a message synchronously, then report the real outcome later (delivered, bounced, failed) by calling back. The first draft treated vendor acceptance as delivered.

## Options

| Option | Summary | Trade-off |
|---|---|---|
| A. Single phase | Vendor 200 = delivered | Simplest; reports success for messages that later bounce |
| B. Poll vendor status APIs | Scheduled job queries each `SENT` message | No inbound endpoint; extra job, vendor API quotas, delayed status |
| **C. Vendor callbacks** | Vendor calls our webhook with the outcome | Matches vendor behavior; one inbound endpoint |

## Decision

**C.**

- A vendor `200` moves a delivery to `SENT` and stores the vendor's message id.
- Every send carries our `deliveryId`; callbacks match on it (`POST /api/v1/webhooks/{provider}`).
- Callback events: `DELIVERED`, `BOUNCED`, `FAILED`. `BOUNCED` is stored as `FAILED` with reason `BOUNCED`.
- No callback: the delivery stays `SENT` indefinitely. No sweeper invents an outcome.
- Retries cover only failed send calls (timeout, 5xx, 429). After a vendor `200` nothing is resent; the vendor owns its retries.
- Dedupe by state: a callback applies only to a `SENDING`, `SENT`, or `RETRY_SCHEDULED` delivery; repeats and late events for final deliveries are audited as `CALLBACK_IGNORED`. Accepting `SENDING` covers a callback that beats our `SENT` write; accepting `RETRY_SCHEDULED` covers a vendor that accepted an attempt we classified as failed, and cancels the retry (revision below).
- Responses are `200` for applied and ignored events so vendors do not retry.
- Webhook auth: shared secret token per provider in `X-Webhook-Token`.
- One generic callback format for the simulated providers: parsed in `api.CallbackRequestValidator`, mapped event -> status in `delivery.DeliveryCallbacks`; provider adapters only send. Production path: a per-provider `CallbackParser` chosen by `WebhookProvider` maps each vendor's payload and verifies its signature (not built).

## Discussion

- Engineer described real vendor behavior: 200 = accepted, delivered/bounce/error arrive by callback. Replaced the draft's single-phase assumption (A-20).
- Engineer confirmed `SENT` stays `SENT` without a callback.
- Engineer challenged HMAC-signed webhooks as unnecessary complexity for the prototype. Replaced with a shared token.
- Independent design review, commissioned by the engineer with over-complication as its focus, found the first version too heavy and racy: a callback-event table, reconciliation of unmatched callbacks (which could lose events between two transactions), and two vendor-specific payload formats. Replaced with matching on our own `deliveryId` and dedupe by state. Engineer chose to fold `BOUNCED` into `FAILED` for simplicity.

Full thread: #4, review record on #5.

## Revision (2026-10-21, #11)

- Engineer decision (A-47): callbacks also apply to `RETRY_SCHEDULED` deliveries. A send we classified as `TIMEOUT` may have been accepted; the vendor's callback is the authoritative outcome. The callback write clears `next_attempt_at` and bumps `version`, so the pending retry's claim fails and nothing is sent twice. `PENDING` (never sent) stays ignored.
- Engineer decision (A-49): a vendor `FAILED` callback is stored as `FAILED` with reason `PROVIDER_FAILED`; the vendor's `reasonCode` is audit-only.
- Engineer decision (A-48): a late callback for a delivery that expired after a lease reclaim keeps `EXPIRED` and is audited `CALLBACK_IGNORED`.

## Consequences

- Plus: statuses match vendor behavior; no duplicate sends after acceptance; no extra table; no reconciliation race.
- Minus: an extra inbound endpoint; `SENT` can be a resting state; shared token is weaker than vendor signatures; callback history is only in the audit log.
