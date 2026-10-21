# Architecture

Notification Management Service: accepts notification requests from source systems, routes each recipient to channels, delivers asynchronously through providers, and tracks status through provider callbacks.

Decisions: `docs/adr/`. Assumptions: `docs/ambiguity-log.md`. Requirement IDs: `docs/requirements.md`.

## Components

```mermaid
flowchart LR
    SRC[Source systems] -->|POST /notifications| API
    SRC -->|GET status / audit| API
    VND[Provider callbacks] -->|POST /webhooks/provider| HOOK

    subgraph NMS[Notification service - single Spring Boot app]
        API[API + auth] --> INTAKE[Intake + idempotency]
        INTAKE --> ROUTE[Routing]
        ROUTE --> DB[(Database)]
        WORKER[Delivery worker] --> DB
        WORKER --> PROV[Provider adapters]
        HOOK[Webhook handler] --> DB
        API --> STATUS[Status + audit queries] --> DB
    end

    PROV -->|send| EXT[Email / SMS vendors - simulated]
    EXT -.->|callback| VND
```

| Component | Responsibility |
|---|---|
| API + auth | REST endpoints, validation, API-key and webhook-token auth, error mapping |
| Intake | Idempotency check, split request into one notification per recipient, persist |
| Routing | Resolve channels per recipient from request, preferences, and policy |
| Delivery worker | Claim due deliveries, call provider, classify result, schedule retries, expire |
| Provider adapters | One per channel: send call, error classification, callback event mapping |
| Webhook handler | Authenticate callback, dedupe, apply final status |
| Status + audit | Read models; overall status derived on read |
| Directory | Seeded recipients, contact addresses, channel preferences |

## API

All endpoints are under `/api/v1`. Callers authenticate with `X-API-Key` (ADR-0001). A source system can only read its own notifications.

| Method | Path | Purpose |
|---|---|---|
| POST | `/notifications` | Submit a notification for 1..100 recipients |
| GET | `/notifications/{id}` | Status of one notification (one recipient) |
| GET | `/notifications/{id}/audit` | Audit history of one notification |
| POST | `/webhooks/{provider}` | Provider delivery callbacks (`X-Webhook-Token`) |
| GET | `/actuator/health`, `/actuator/prometheus` | Operations |

### Submit

Headers: `X-API-Key` (required), `Idempotency-Key` (required).

```json
{
  "eventId": "evt-778",
  "type": "FRAUD_ALERT",
  "severity": "CRITICAL",
  "priority": "HIGH",
  "recipients": [
    { "userId": "user1", "channels": ["SMS"] },
    { "userId": "user2", "channels": ["EMAIL"] },
    { "userId": "user3" }
  ],
  "subject": "Suspicious login",
  "body": "We noticed a login from a new device.",
  "scheduledAt": null,
  "expiresAt": "2026-10-21T10:00:00Z"
}
```

- Required (spec 4.1): `eventId`, `type`, `severity` (INFO, WARNING, CRITICAL), `priority` (LOW, NORMAL, HIGH), `recipients`, `subject`, `body`. Optional: `scheduledAt`, `expiresAt`, per-recipient `channels`. No server-side defaults for spec fields.
- `severity` drives routing; `priority` drives worker claim order under backlog.
- Each recipient carries its own optional `channels`. When absent, the recipient's enabled channels from the directory are used. Contact addresses always come from the directory, never the request.
- One notification is created per recipient. All notifications from one request share an internal `submissionId` (not exposed).
- `202` on a new submission, `200` on an idempotent replay (same key, same payload) with the same body.

```json
{
  "notifications": [
    { "recipientId": "user1", "notificationId": "6f1c...", "status": "ACCEPTED" },
    { "recipientId": "user2", "notificationId": "a83d...", "status": "ACCEPTED" }
  ]
}
```

### Status

```json
{
  "notificationId": "6f1c...",
  "recipientId": "user1",
  "eventId": "evt-778",
  "type": "FRAUD_ALERT",
  "severity": "CRITICAL",
  "overallStatus": "PARTIALLY_DELIVERED",
  "duplicateSubmissions": 1,
  "createdAt": "...", "scheduledAt": null, "expiresAt": "...",
  "deliveries": [
    { "channel": "EMAIL", "status": "DELIVERED", "attempts": 2, "sentAt": "...", "finalizedAt": "..." },
    { "channel": "SMS", "status": "FAILED", "reason": "INVALID_RECIPIENT", "attempts": 1, "finalizedAt": "..." }
  ]
}
```

### Audit

```json
{
  "events": [
    { "at": "...", "type": "ACCEPTED" },
    { "at": "...", "type": "ROUTED", "channel": "SMS", "detail": "added: severity CRITICAL" },
    { "at": "...", "type": "ATTEMPT_FAILED", "channel": "EMAIL", "detail": "TRANSIENT, attempt 1" },
    { "at": "...", "type": "RETRY_SCHEDULED", "channel": "EMAIL", "detail": "in 2s" },
    { "at": "...", "type": "DUPLICATE_SUBMISSION", "detail": "same Idempotency-Key; original returned" },
    { "at": "...", "type": "DELIVERED", "channel": "EMAIL" }
  ]
}
```

### Provider callbacks

Each provider adapter owns its payload format and the mapping to our statuses. Simulated formats follow the shape of common vendors.

`POST /webhooks/email` (batched, SendGrid-style):

```json
[
  { "eventId": "e-01", "messageId": "em-123", "event": "delivered", "timestamp": "..." },
  { "eventId": "e-02", "messageId": "em-456", "event": "bounce", "reason": "mailbox unavailable", "timestamp": "..." }
]
```

`POST /webhooks/sms` (single event, Twilio-style; dedupe key is `messageSid` + `messageStatus`):

```json
{ "messageSid": "SM-789", "messageStatus": "undelivered", "errorCode": "30003", "timestamp": "..." }
```

Response: `200` with no body for processed, duplicate, ignored, and unmatched events, so vendors do not retry them. Vendor free-text reasons are mapped to reason codes; raw text is not stored because it can contain addresses.

### Errors

Every error, including authentication failures raised before any controller, uses one body (`Content-Type: application/problem+json`; all RFC 9457 members are optional, so this stays compliant):

```json
{
  "traceId": "8f3c2a1b9d",
  "errors": [
    { "code": 4001, "field": "Idempotency-Key", "message": "Required header is missing" },
    { "code": 4004, "field": "recipients[2].userId", "message": "Must not be blank" }
  ]
}
```

- All validation errors are returned together.
- Submitted values are never echoed back.
- `code` is a stable contract shared across subsystems; `message` may change.

| Code | HTTP | Meaning |
|---|---|---|
| 4001 | 400 | Required header missing |
| 4002 | 400 | Malformed JSON |
| 4003 | 400 | Invalid field value or type |
| 4004 | 400 | Required field missing |
| 4005 | 400 | Too many recipients (max 100) |
| 4011 | 401 | API key or webhook token missing |
| 4012 | 401 | API key or webhook token invalid (expired and revoked are deliberately indistinguishable) |
| 4041 | 404 | Notification not found (also returned for another source system's notification) |
| 4091 | 409 | Idempotency key reused with a different payload |
| 4092 | 409 | Same idempotency key is still being processed |
| 4151 | 415 | Unsupported content type |
| 4221 | 422 | `expiresAt` is in the past |
| 4222 | 422 | `scheduledAt` is after `expiresAt` |
| 4223 | 422 | Unknown recipient (whole request rejected, every unknown recipient listed) |
| 5000 | 500 | Internal error (no detail, `traceId` only) |

## Data model

```mermaid
erDiagram
    IDEMPOTENCY_RECORD ||--|{ NOTIFICATION : "submissionId"
    NOTIFICATION ||--|{ DELIVERY : "one per channel"
    DELIVERY ||--o{ DELIVERY_ATTEMPT : "one per send call"
    DELIVERY ||--o{ PROVIDER_EVENT : "callbacks"
    NOTIFICATION ||--o{ AUDIT_EVENT : "history"
    RECIPIENT ||--o{ CHANNEL_PREFERENCE : "per channel"
    RECIPIENT ||--o{ NOTIFICATION : "recipientId"
```

Conventions: timestamps are UTC instants; ids exposed by the API are UUIDs (not guessable or enumerable); internal tables use identity keys; enums are stored as strings.

### Directory (seeded, read-only)

`recipient`: the only table holding contact details.

| Column | Type | Notes |
|---|---|---|
| `id` | varchar(64) PK | Caller's user id |
| `email` | varchar(254) null | |
| `phone` | varchar(20) null | E.164 |

`channel_preference`

| Column | Type | Notes |
|---|---|---|
| `recipient_id`, `channel` | PK | |
| `enabled` | boolean | `false` = opted out |

A missing preference row means **enabled** (opt-out model, usual for transactional alerts). A channel is usable only if enabled and the recipient has an address for it.

### Intake

`idempotency_record`

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `source_system`, `idempotency_key` | varchar(64), varchar(128) | Unique together. Longer keys rejected (4003) |
| `request_hash` | char(64) | SHA-256 of the body; detects same key with different payload |
| `submission_id` | uuid | Links to the notifications created |
| `created_at`, `expires_at` | timestamp | `expires_at` indexed for the purge job (7 days) |

`notification`: one per recipient.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | Returned as `notificationId` |
| `submission_id` | uuid, indexed | Groups notifications from one request |
| `source_system` | varchar(64) | From the API key; used for tenant-scoped reads |
| `event_id`, `type` | varchar(128), varchar(64) | |
| `severity`, `priority` | varchar(16) | Required (spec 4.1) |
| `recipient_id` | FK `recipient` | |
| `requested_channels` | varchar(64) null | As requested, e.g. `SMS,EMAIL`; null = preferences. Explains requested vs selected |
| `subject`, `body` | varchar(200), varchar(5000) | Sensitive; stored only here, plaintext in the prototype |
| `scheduled_at`, `expires_at` | timestamp null | |
| `created_at` | timestamp | |

No status column: overall status is derived from deliveries.

### Delivery

`delivery`: one per (notification, channel); the worker's unit of work.

| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | Also the provider idempotency key |
| `notification_id`, `channel` | FK, varchar(16) | Unique together |
| `provider` | varchar(32) | e.g. `sim-email`; separate from channel so providers can change per channel |
| `status` | varchar(20) | `PENDING`, `SENDING`, `RETRY_SCHEDULED`, `SENT`, `DELIVERED`, `BOUNCED`, `FAILED`, `EXPIRED` |
| `routing_reason` | varchar(32) | `REQUESTED`, `PREFERENCE`, `SEVERITY_ESCALATION` |
| `attempt_count` | int | |
| `next_attempt_at` | timestamp null | Due time; null once final. Indexed with `status` for polling |
| `claimed_at` | timestamp null | Lease start; stale `SENDING` rows are reclaimable |
| `provider_message_id` | varchar(128) null | Set on `SENT`; unique with `provider` |
| `failure_reason` | varchar(32) null | e.g. `INVALID_RECIPIENT`, `BOUNCED` |
| `sent_at`, `finalized_at`, `created_at`, `updated_at` | timestamp | |
| `version` | bigint | Optimistic lock; only one claim succeeds |

`delivery_attempt`: one per send call, never updated.

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `delivery_id`, `attempt_no` | FK, int | Unique together |
| `outcome` | varchar(20) | `ACCEPTED`, `RETRYABLE_FAILURE`, `FINAL_FAILURE` |
| `error_category` | varchar(24) null | `TRANSIENT`, `TIMEOUT`, `RATE_LIMITED`, `PERMANENT_REJECTION`, `INVALID_RECIPIENT`, `AUTH_ERROR` |
| `provider_code` | varchar(32) null | Vendor's raw code, e.g. `429` |
| `started_at`, `duration_ms` | timestamp, int | |

`provider_event`: callback log.

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `provider`, `event_key` | varchar(32), varchar(160) | Unique together: duplicate callbacks are rejected here. Email: vendor event id. SMS: `messageSid` + status |
| `provider_message_id` | varchar(128), indexed | Matches the delivery |
| `raw_status`, `mapped_status` | varchar(32), varchar(20) null | Null mapped status = ignored (e.g. `open`) |
| `reason_code` | varchar(32) null | Mapped code; raw vendor text not stored |
| `delivery_id` | uuid null | Null = unmatched; set when the `SENT` write commits |
| `occurred_at`, `received_at` | timestamp | |

### Audit

`audit_event`: append-only; code never updates or deletes.

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `source_system` | varchar(64) | |
| `notification_id` | uuid null, indexed | Null for `REJECTED` requests, which create no notification (spec 4.9) |
| `delivery_id` | uuid null | |
| `type` | varchar(32) | `ACCEPTED`, `REJECTED`, `DUPLICATE_SUBMISSION`, `ROUTED`, `ROUTE_REMOVED`, `NOT_ROUTED`, `ATTEMPT_FAILED`, `RETRY_SCHEDULED`, `SENT`, `DELIVERED`, `BOUNCED`, `FAILED`, `EXPIRED`, `CALLBACK_IGNORED` |
| `channel` | varchar(16) null | |
| `detail` | varchar(256) | Codes and reasons only; never content or addresses |
| `occurred_at` | timestamp | |

API keys and webhook tokens are configuration, not tables (ADR-0001). Production path for content: encryption at rest and clearing `body` once all deliveries are final.

## Routing

Resolved per notification (one recipient):

1. Candidates: the recipient's `channels` from the request if given, else the recipient's enabled channels.
2. Remove channels the recipient opted out of, and channels with no contact address.
3. Apply policy from config, e.g. `CRITICAL` adds `SMS`. In greenfield an opt-out always wins over policy (revisited in the ambiguous scenario, A-30).
4. Each remaining channel becomes a `Delivery`. Every add and remove is audited with a reason.
5. No channel left: no deliveries, overall status `NOT_ROUTED`.

## Delivery lifecycle

Two-phase: a vendor `200` means accepted (`SENT`); the final outcome arrives by callback (ADR-0003).

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> SENDING: claimed by worker
    RETRY_SCHEDULED --> SENDING: due
    SENDING --> SENT: vendor 200
    SENDING --> RETRY_SCHEDULED: transient / timeout / rate limited
    SENDING --> FAILED: permanent / invalid recipient / auth error / attempts exhausted
    PENDING --> EXPIRED: past expiresAt
    RETRY_SCHEDULED --> EXPIRED: past expiresAt
    SENT --> DELIVERED: callback
    SENT --> BOUNCED: callback (email)
    SENT --> FAILED: callback
```

- `SENT` with no callback stays `SENT` indefinitely. That is the honest state.
- After `SENT` nothing is resent; the vendor owns its own retries.
- Final states never change. Late or duplicate callbacks are ignored and audited.

### Failure classification (send call)

| Category | Example | Action |
|---|---|---|
| `TRANSIENT` | 5xx, connection reset | Retry with backoff |
| `TIMEOUT` | No response in time | Retry with backoff; same provider idempotency key (`deliveryId`) |
| `RATE_LIMITED` | 429 | Retry after `Retry-After`, else backoff |
| `PERMANENT_REJECTION` | Vendor refuses content | `FAILED` |
| `INVALID_RECIPIENT` | Bad address or number | `FAILED` |
| `AUTH_ERROR` | Vendor rejects our credentials | `FAILED`, error log, metric (config problem, not delivery problem) |

Retries: max 4 attempts (configurable), exponential backoff with full jitter, never scheduled past `expiresAt`.

### Overall status (derived on read)

| Deliveries | `overallStatus` |
|---|---|
| Before `scheduledAt` | `SCHEDULED` |
| Any `PENDING`, `SENDING`, `RETRY_SCHEDULED` | `IN_PROGRESS` |
| Otherwise any `SENT` | `AWAITING_CONFIRMATION` |
| All `DELIVERED` | `DELIVERED` |
| Some `DELIVERED`, rest failed, bounced, or expired | `PARTIALLY_DELIVERED` |
| None delivered, all `EXPIRED` | `EXPIRED` |
| None delivered, otherwise | `FAILED` |
| No deliveries | `NOT_ROUTED` |

"Delivered" means every routed channel was delivered, not "recipient reached on some channel" (A-22).

## Control flow

### Submit

```mermaid
sequenceDiagram
    participant S as Source system
    participant A as API
    participant I as Intake
    participant D as Database
    S->>A: POST /notifications (X-API-Key, Idempotency-Key)
    A->>A: authenticate, validate
    A->>I: submit
    I->>D: insert IdempotencyRecord (unique sourceSystem + key)
    alt key exists, same hash
        I->>D: audit DUPLICATE_SUBMISSION
        I-->>S: 200 + original notifications
    else key exists, different hash
        I-->>S: 409 / 4091
    else new
        I->>D: per recipient: Notification, routed Deliveries (PENDING), audit events
        I-->>S: 202 + notifications
    end
```

One transaction per submission: the idempotency record and all notifications commit together or not at all.

### Deliver

```mermaid
sequenceDiagram
    participant W as Worker
    participant D as Database
    participant P as Provider adapter
    loop every poll interval
        W->>D: select due deliveries (PENDING / RETRY_SCHEDULED, nextAttemptAt <= now) by priority
        W->>D: claim: status = SENDING where id and version match
        W->>P: send (idempotency key = deliveryId)
        alt 200
            W->>D: SENT + providerMessageId, apply any unmatched callbacks
        else retryable
            W->>D: RETRY_SCHEDULED, nextAttemptAt = now + backoff
        else final
            W->>D: FAILED + reason
        end
        W->>D: DeliveryAttempt + audit events
    end
```

- Claiming is a conditional update on `version`, so two workers can never send the same delivery.
- A delivery stuck in `SENDING` longer than the lease (crash mid-send) becomes claimable again. The resend reuses the same provider idempotency key.

### Callback

```mermaid
sequenceDiagram
    participant V as Vendor
    participant H as Webhook handler
    participant D as Database
    V->>H: POST /webhooks/{provider} (X-Webhook-Token)
    H->>H: authenticate; adapter maps vendor event to our status
    H->>D: insert ProviderEvent (unique provider + eventKey)
    alt duplicate event
        H-->>V: 200 (ignored)
    else delivery found and SENT
        H->>D: final status + finalizedAt + audit
        H-->>V: 200
    else delivery not found yet
        H->>D: keep ProviderEvent unmatched (applied when SENT commits)
        H-->>V: 200
    end
```

Unmatched events cover the race where a vendor calls back before our `SENT` write commits.

## Security

- Source systems: hashed, rotatable API keys; `sourceSystem` comes from the key, never the body (ADR-0001).
- Webhooks: shared secret token per provider in `X-Webhook-Token`, checked the same way. Production: verify each vendor's own signature (ADR-0003).
- Tenant isolation: reads are scoped to the caller's source system; other ids return 404.
- Input limits: max 100 recipients, bounded `subject` and `body` length, enum validation, request size limit.
- Sensitive data: message content and contact details never appear in logs, audit events, or error bodies. Audit and logs reference ids only.
- Secrets come from environment variables; nothing secret is committed.
- Actuator exposes only `health`, `info`, `prometheus`. The H2 console is off outside the `dev` profile.

## Operations

| Signal | Detail |
|---|---|
| Trace id | Generated per request (or taken from `traceparent`), in MDC, logs, `X-Trace-Id` response header, and error bodies |
| Logs | Structured JSON; ids and outcome categories only |
| Health | `/actuator/health` (database, worker liveness) |
| Metrics | `nms.notifications.accepted`, `nms.deliveries.outcome{channel,status}`, `nms.delivery.attempts{channel,category}`, `nms.deliveries.due` (backlog gauge), `nms.webhook.events{provider,result}`, `nms.auth.failures` |

What to alert on:
- `AUTH_ERROR` from a provider: our credentials are wrong; every delivery on that channel will fail.
- Growing `nms.deliveries.due`: workers are not keeping up or a provider is down.
- Spike in unmatched or rejected webhook events: misconfiguration or forged callbacks.

## Scaling path (not built)

| Concern | Prototype | Production |
|---|---|---|
| Database | H2 | PostgreSQL |
| Work claiming | Conditional update on `version` | `SELECT ... FOR UPDATE SKIP LOCKED`, or a broker (Kafka/SQS) with the database as outbox |
| Throughput | One instance, worker thread pool | Horizontal instances; per-channel worker pools |
| Provider limits | Retry on 429 | Token bucket per provider |
| Auth | Hashed API keys in config | HMAC request signing or JWT; secrets manager |
| Retention | Idempotency purge job | Partitioned audit tables, archival |

## Out of scope

Cancel, list/search, preferences API, templates and localization, engagement events (open/click), quiet hours, bulk sends above 100 recipients, real vendor integrations.
