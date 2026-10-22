# Notification Management Service

[![CI](https://github.com/radwara/nms/actions/workflows/ci.yml/badge.svg)](https://github.com/radwara/nms/actions/workflows/ci.yml)

Prototype service that accepts notification requests from source systems, routes each recipient to channels (requested channels, preferences, severity policy), delivers asynchronously through simulated email and SMS providers with bounded retries, and tracks the final outcome through provider callbacks. Built as one Spring Boot app on H2 in three scenarios: greenfield, brownfield (event-level dedup), and an ambiguous requirement (CRITICAL vs opt-out). Feature and scenario work went issue -> PR -> CI -> independent review -> engineer merge. Each CI run's summary page shows test counts and line coverage.

## Quick start

Requires Java 21. No other installs.

```
./mvnw verify            # format check, build, unit + integration tests, coverage
./mvnw spring-boot:run   # http://localhost:8080/actuator/health
```

Event dedup is on by default (`NMS_DEDUP_ENABLED`, `NMS_DEDUP_WINDOW`, default `24h`; ADR-0006). Outbound sends are rate limited per source system and channel (`nms.rate-limit.*`: EMAIL 600, SMS 60, other channels 30 per minute; ADR-0007). Without a profile no API keys are configured (`NMS_API_KEYS` is empty), so every `/api` call returns 401. Use the `dev` profile below to try the API.

Kafka mode (ADR-0008, off by default; needs a broker): `NMS_KAFKA_ENABLED=true SPRING_KAFKA_BOOTSTRAP_SERVERS=host:9092 ./mvnw spring-boot:run`. EMAIL first dispatch then goes through topic `nms.delivery.email`; the poller sends anything Kafka missed after `nms.kafka.poller-grace` (30s). Tests use an in-JVM broker; no install needed.

## Docker

The image copies the host-built jar (`target/nms-*.jar`), so build it first:

```
./mvnw verify
docker build -t nms .
docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev nms   # dev profile: demo credentials, simulated callbacks
```

Runs as a non-root user; data in `/app/data` (`NMS_DATA_DIR`). CI builds the image and checks the user; Docker is not installed on the development machine, so `docker run` was not tried locally.

## Run the demo

Needs `curl` and `jq`. The delivery worker is on by default; the `dev` profile adds the simulated vendors' callbacks, the documented demo credentials (ADR-0001), the H2 console, and plain-text logs.

```
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # terminal 1; H2 file database in ./data (NMS_DATA_DIR: absolute path)
scripts/demo.sh                                         # terminal 2; NMS_BASE_URL, NMS_API_KEY, DEMO_TIMEOUT override the defaults
```

Nine steps against the HTTP API: health, happy path, routing (requested channel, severity escalation, opt-out), retry, duplicate submission, event dedup (same event and message under a new key is `SUPPRESSED`; a new message for the same event is delivered), bounce, a validation error, an authentication error. Steps 2-7 poll status until final (30 s timeout); each step uses its own `eventId`, and step 6 reuses it on purpose. The script exits non-zero on any unexpected result. CI runs it against the built jar. Excerpt:

```
== 3. Routing: CRITICAL adds SMS and overrides an SMS opt-out (A-30)
   POST /api/v1/notifications  key=demo-1792642057-244-routing  -> 202
        user-ok ACCEPTED 51c807e3-...
        user-sms-optout ACCEPTED 059815ec-...
   GET  /api/v1/notifications/51c807e3-...  -> 200  user-ok  overallStatus=DELIVERED  requestedChannels=["EMAIL"]  duplicateSubmissions=0
        EMAIL  DELIVERED  attempts=1
        SMS  DELIVERED  attempts=1
   GET  /api/v1/notifications/51c807e3-.../audit  -> 200
        ACCEPTED ROUTED(EMAIL) ROUTED(SMS) QUEUED(EMAIL) QUEUED(SMS) SENT(EMAIL) SENT(SMS)
        DELIVERED(EMAIL) DELIVERED(SMS)
        routing: ROUTED EMAIL: REQUESTED
        routing: ROUTED SMS: SEVERITY_ESCALATION
   GET  /api/v1/notifications/059815ec-...  -> 200  user-sms-optout  overallStatus=DELIVERED  requestedChannels=none  duplicateSubmissions=0
        EMAIL  DELIVERED  attempts=1
        SMS  DELIVERED  attempts=1
   GET  /api/v1/notifications/059815ec-.../audit  -> 200
        ACCEPTED ROUTED(EMAIL) ROUTED(SMS) QUEUED(EMAIL) QUEUED(SMS) SENT(EMAIL) SENT(SMS)
        DELIVERED(EMAIL) DELIVERED(SMS)
        routing: ROUTED EMAIL: PREFERENCE
        routing: ROUTED SMS: OPT_OUT_OVERRIDDEN_CRITICAL
   OK

== 4. Retry path: user-flaky2, two transient failures (503), then accepted and delivered
   ...
        EMAIL  DELIVERED  attempts=3
        SMS  DELIVERED  attempts=3
   ...
== 6. Event dedup: same event and message under a new key is suppressed; a new message is delivered
   POST /api/v1/notifications  key=demo-1792643895-24833-dedup-1  -> 202
        user-ok ACCEPTED 0c4fd20d-...
   POST /api/v1/notifications  key=demo-1792643895-24833-dedup-2  -> 202
        user-ok SUPPRESSED a13d0139-...  duplicateOf=0c4fd20d-...
   POST /api/v1/notifications  key=demo-1792643895-24833-dedup-3  -> 202
        user-ok ACCEPTED fa914b5d-...
   GET  /api/v1/notifications/a13d0139-...  -> 200  user-ok  overallStatus=SUPPRESSED  requestedChannels=none  duplicateSubmissions=0  duplicateOf=0c4fd20d-...
   GET  /api/v1/notifications/a13d0139-.../audit  -> 200
        SUPPRESSED
   GET  /api/v1/notifications/0c4fd20d-.../audit  -> 200
        ACCEPTED ROUTED(EMAIL) ROUTED(SMS) QUEUED(EMAIL) QUEUED(SMS) DUPLICATE_EVENT SENT(EMAIL)
        SENT(SMS) DELIVERED(EMAIL) DELIVERED(SMS)
   ...
   OK
...
== 8. Validation error: submit without Idempotency-Key
   POST /api/v1/notifications  (no Idempotency-Key)  -> 400  [{"code":4001,"field":"Idempotency-Key","message":"Required header is missing"}]
   OK
...
Demo passed: 9 steps.
```

## Reviewer guide

One section per evaluation criterion in the assignment. Each bullet points to the evidence.

### AI-assisted engineering
- Roles and loop: engineer, orchestrator, implementer, reviewers ([`docs/process.md`](docs/process.md) Roles, Per-issue loop); agent rules in [`AGENTS.md`](AGENTS.md).
- Every PR has an AI-usage note (generated / changed / rejected) and a "Review record" comment, e.g. [#41](https://github.com/radwara/nms/pull/41), [#45](https://github.com/radwara/nms/pull/45).
- What AI got wrong and what the engineer changed: [AI usage](#ai-usage) below.

### Architecture and system design
- [`docs/architecture.md`](docs/architecture.md): components, API contracts, data model, state machines, control flow, Key decisions table.
- Six ADRs with rejected options: [`docs/adr/`](docs/adr/) (auth, per-recipient model, two-phase status, DB queue, stack, event dedup).
- Brownfield impact analysis merged before any code: [`docs/scenarios/brownfield.md`](docs/scenarios/brownfield.md) (PR [#31](https://github.com/radwara/nms/pull/31)).

### Decomposition and execution
- Backlog of issues with acceptance criteria linked to requirement IDs; build order reviewed and changed before code ([backlog review](https://github.com/radwara/nms/issues/6#issuecomment-6308373952)).
- One branch and PR per issue, per-PR commits and reviewers: [`greenfield.md`](docs/scenarios/greenfield.md) Execution.
- Brownfield: behaviour-preserving refactor commit `3d9a72c` first, zero test diff, CI green on it alone ([`brownfield.md`](docs/scenarios/brownfield.md) Execution).

### Realism and quality of outputs
- Two-phase delivery that matches real vendors: `SENT` on vendor 200, final status by callback ([ADR-0003](docs/adr/0003-two-phase-delivery-status.md)).
- Simulated providers derive every failure class from the seeded address (architecture.md "Simulated outcomes"); simulated vendors call back over real HTTP.
- `scripts/demo.sh`: nine flows against the running jar, run in CI on every PR.
- Spotless, `-Xlint:all -Werror`, ArchUnit package rules (`PackageDependencyTest`) fail the build.

### Validation and risk management
- Traceability matrix: every requirement mapped to tests in [`docs/requirements.md`](docs/requirements.md); untestable rows say why.
- `GreenfieldE2EIT`: every flow over HTTP with the real poller and callbacks, plus a leak check of all audit rows and logs.
- 10 bugs filed and fixed before merge, 9 `found-by:review`, 1 `found-by:test` ([list](https://github.com/radwara/nms/issues?q=label%3Abug)).
- Brownfield regression risk and rollout ([`brownfield.md`](docs/scenarios/brownfield.md) sections 5, 7); off switch `NMS_DEDUP_ENABLED`.

### Clear, defensible decisions
- Every assumption with its status: [`docs/ambiguity-log.md`](docs/ambiguity-log.md); engineer decisions marked, with the issue or PR.
- Ambiguous requirement: options, stakeholder questions, decision, limitations ([`docs/scenarios/ambiguous.md`](docs/scenarios/ambiguous.md), PR [#43](https://github.com/radwara/nms/pull/43)).
- Engineer decision tables: [`greenfield.md`](docs/scenarios/greenfield.md) section 4, [`brownfield.md`](docs/scenarios/brownfield.md) Engineer decisions.

### Core principles
- Modular, testable: one module packaged by feature, rules enforced by `PackageDependencyTest` ([ADR-0005](docs/adr/0005-technology-stack.md)); unit tests without a Spring context.
- Reliable: claim committed before send, versioned conditional writes, lease reclaim, bounded retries ([ADR-0004](docs/adr/0004-database-backed-delivery-queue.md); `DeliveryQueueIT`, `DeliveryWorkerIT`).
- Secure: [Security](#security) below. Scalable: path documented, not built (architecture.md "Scaling path").
- Safe change: issue -> PR -> CI -> review for every change ([`docs/process.md`](docs/process.md)); additive V3 migration and an off switch for the brownfield feature.

### Engineering judgment
- Kept simple on purpose: plain filter over Spring Security, no key tooling (ADR-0001 revision); shared webhook token over HMAC, no callback-event table (ADR-0003).
- Exactly-once rejected as unachievable: at-least-once with the `deliveryId` as provider idempotency key (A-37).
- Dedup race documented instead of locked: fails open (one extra message), never drops a different message (ADR-0006, `EventDedupRaceIT`).

## Testing approach

Tests are written from each issue's acceptance criteria before or with the code; review findings add regression tests.

| Layer | What |
|---|---|
| Unit (`*Test`, 33 classes) | Pure logic without Spring: validation, hashing, routing decisions, retry policy, overall status table, package rules |
| Integration (`*IT`, 42 classes) | Full Spring context, own in-memory H2 per context: API contracts, idempotency races, worker, callbacks, metrics, logs |
| End to end | `GreenfieldE2EIT` (real server, background poller, HTTP callbacks); `scripts/demo.sh` against the jar in CI |
| Measured | Statement counts (`SubmitStatementsIT`), index plans (`EventDedupLookupIT`), poll scan sizes (#32) |

Final run (`./mvnw -B verify`): **317 unit + 280 integration tests, 0 failures; 96.4% line coverage, 90.1% branch** (JaCoCo, unit + IT merged; report in `target/site/jacoco/index.html`).

## Operating it

Signals, metrics, and what to alert on: [architecture.md "Operations"](docs/architecture.md#operations). Main settings (environment):

| Variable | Default | Effect |
|---|---|---|
| `NMS_API_KEYS`, `NMS_WEBHOOK_TOKENS` | empty (401 for all) | Hashed credentials (ADR-0001 shows how to generate) |
| `NMS_DATA_DIR` | `./data` | H2 file location |
| `NMS_WORKER_ENABLED` | `true` | Off: deliveries stay queued |
| `NMS_DEDUP_ENABLED`, `NMS_DEDUP_WINDOW` | `true`, `24h` | Event dedup off switch and window (restart to apply) |
| `NMS_IDEMPOTENCY_RETENTION` | `7d` | How long an `Idempotency-Key` is honored |

## Security

Details: [architecture.md "Security"](docs/architecture.md#security).
- Hashed, rotatable API keys; `sourceSystem` from the key; tenant-scoped reads (404 for other tenants).
- Webhooks: per-provider token, fail-closed; dot-segment paths rejected before auth.
- Input validated and bounded (64 KB body, 100 recipients, field limits); values never echoed or logged.
- No content, contact details, or credentials in logs or audit (leak check in `GreenfieldE2EIT`); secrets from environment; gitleaks, Dependabot, non-root image in CI.

## Limitations

| Limitation | Source |
|---|---|
| At-least-once delivery: a crash after vendor acceptance resends; the vendor dedupes on `deliveryId` | A-37, ADR-0004 |
| `SENT` is a resting state when no callback arrives | ADR-0003 |
| A delivery that expired after a lease reclaim may have been sent; a late callback is audited, not applied | A-48 |
| H2 locking differs from PostgreSQL; lock waits rely on H2 `LOCK_TIMEOUT` | ADR-0005, architecture.md "Scaling path" |
| Two concurrent repeats of an event under different keys are both delivered; reworded text is not caught | ADR-0006 |
| Single instance, single-threaded poller; scaling path not built | ADR-0004 |
| Content stored in plaintext; no purge of idempotency or audit rows | architecture.md "Data model" |
| `/actuator/prometheus` needs no key; one generic callback format, no vendor signatures | architecture.md "Security", ADR-0003 |
| Carrier SMS STOP not modeled; a misused CRITICAL reaches opted-out channels | [`ambiguous.md`](docs/scenarios/ambiguous.md) |
| V3 migration verified on H2 only; Docker image verified in CI only | `brownfield.md`, Docker above |
| A-01, A-02 unconfirmed (no reply from the hiring team) | `ambiguity-log.md` |

## Trade-offs

| Chose | Over | Because | Record |
|---|---|---|---|
| Hashed API keys | JWT, HMAC signing | No identity provider; leaked config leaks no usable key | [ADR-0001](docs/adr/0001-api-authentication.md) |
| One notification per recipient | One notification, N recipients | Per-user status is one lookup; content copied per recipient | [ADR-0002](docs/adr/0002-per-recipient-notifications-and-idempotency.md) |
| Vendor callbacks | Single phase, polling vendors | Honest status; extra inbound endpoint | [ADR-0003](docs/adr/0003-two-phase-delivery-status.md) |
| Database table as queue | Broker, in-memory queue | Durable, no infrastructure; polling latency and DB load | [ADR-0004](docs/adr/0004-database-backed-delivery-queue.md) |
| H2 | PostgreSQL + Testcontainers | Runs with one command, no Docker; different locking | [ADR-0005](docs/adr/0005-technology-stack.md) |
| Content hash in the dedup key | `eventId` key, shadow mode | A different message is never dropped; reworded resends are not caught | [ADR-0006](docs/adr/0006-event-level-deduplication.md) |
| CRITICAL overrides opt-out (fixed rule) | Opt-out wins, per-category consent | Critical alerts reach the user; no schema change | [`ambiguous.md`](docs/scenarios/ambiguous.md) |

## AI usage

Summary across PRs; each PR's AI-usage note and review record has the details.

**Generated by AI:** all production code and tests, CI workflow, Dockerfile, demo script, and drafts of every doc (from the engineer's inputs and decisions). Implementation by an implementer subagent from an orchestrator brief; reviews by fresh-context reviewer subagents. 147 review findings recorded on PRs #17-#45.

**What AI got wrong** (all caught before merge):

| Where | Defect | Caught by |
|---|---|---|
| [#28](https://github.com/radwara/nms/issues/28) | Claim token on a timestamp; failed on Linux (precision) | Review |
| [#29](https://github.com/radwara/nms/issues/29), [#30](https://github.com/radwara/nms/issues/30) | `Idempotency-Key` and path values leaked into logs; malformed path -> 500 | Review |
| [#36](https://github.com/radwara/nms/issues/36) | Opt-out dropped without an audit event | Review |
| [#37](https://github.com/radwara/nms/issues/37), [#38](https://github.com/radwara/nms/issues/38) | Poison rows stalled the queue; unbounded retries when a provider throws | Review |
| [#39](https://github.com/radwara/nms/issues/39), [#40](https://github.com/radwara/nms/issues/40) | Callback timestamp before `SENT`; duplicate vendor message id -> 500 | Review |
| [#33](https://github.com/radwara/nms/issues/33) | Log-format test depended on test order | CI (Linux) |
| [#44](https://github.com/radwara/nms/issues/44) | Dedup blocked a corrected resend of a never-sent notification for 24h | Review |
| PR [#5](https://github.com/radwara/nms/pull/5) | Design draft: vendor 200 = delivered; status example contradicting A-21; request did not tie users to channels | Engineer |
| PR [#31](https://github.com/radwara/nms/pull/31) | Proposed `eventId` dedup key would drop a second alert reusing a correlation id | Review, engineer |
| PR [#41](https://github.com/radwara/nms/pull/41) | Race and metrics tests used stub routing (would pass without routing) | Implementer |
| PR [#17](https://github.com/radwara/nms/pull/17) | Formatter version broken on JDK 21; Boot 4 metrics module missing | Build |

**Engineer decided, changed, or rejected:**
- Design ([#5](https://github.com/radwara/nms/pull/5), ADR-0002, ADR-0003): one notification per recipient, internal `submissionId`, per-recipient `channels`, required `Idempotency-Key`, two-phase status, shared webhook token instead of HMAC; commissioned the over-complication review that simplified ADR-0001 and ADR-0003.
- ADR-0001 ([#3](https://github.com/radwara/nms/pull/3)): production path names HMAC and JWT (draft: OAuth2 only).
- Retry policy ([#32](https://github.com/radwara/nms/pull/32#discussion_r4343946188)): 3 attempts, 500 ms base, equal jitter, `Retry-After` cap 60 s, no default TTL (draft: 4 attempts, 2 s, cap 1 h).
- State model ([#34](https://github.com/radwara/nms/pull/34#discussion_r4344290584)): A-24, A-47 (changed: callbacks apply to `RETRY_SCHEDULED`), A-48, A-49.
- Dedup key with content hash; shadow mode and severity rule rejected ([#31](https://github.com/radwara/nms/pull/31#discussion_r4344120633)); D3 option B ([#41](https://github.com/radwara/nms/pull/41#discussion_r4350865614)).
- CRITICAL overrides opt-out on every channel, fixed rule, config switch rejected ([#43](https://github.com/radwara/nms/pull/43), [#45](https://github.com/radwara/nms/pull/45)); approved the one unplanned test change (`SubmitNotificationIT`, 4 -> 5 deliveries).
- Approved AI proposals: idempotency check before time 422s, 409 / 4092 for a concurrent key ([#26](https://github.com/radwara/nms/pull/26#discussion_r4339680007)); kept both routing audit events ([#27](https://github.com/radwara/nms/pull/27#discussion_r4342566572)); rejected SHA pinning of actions ([#17](https://github.com/radwara/nms/pull/17)).
- Ran the demo on the milestone PRs (#35, #41, #45) and recorded the result in the PR.

## How this was built

<!-- ENGINEER: working mode, in your words -->

Time spent: <!-- ENGINEER: time spent (focused hours) -->

Tools: Claude Code (Claude Opus) for orchestration, implementation and reviews; GitHub Actions for CI.

How to tell who did what: all GitHub posts come from one account. PR sections and comments starting with `Engineer:` are the engineer's; comments headed `Review record` are AI review summaries posted by the orchestrator; bugs found by review are labelled `found-by:review` (also `found-by:test`, `found-by:engineer`). Roles: [`docs/process.md`](docs/process.md).

## Docs

- Architecture: [`docs/architecture.md`](docs/architecture.md)
- Decisions: [`docs/adr/`](docs/adr/)
- Requirements and traceability: [`docs/requirements.md`](docs/requirements.md); assumptions: [`docs/ambiguity-log.md`](docs/ambiguity-log.md)
- Process: [`docs/process.md`](docs/process.md); agent rules: [`AGENTS.md`](AGENTS.md)
- Scenarios: [`greenfield.md`](docs/scenarios/greenfield.md), [`brownfield.md`](docs/scenarios/brownfield.md), [`brownfield-2.md`](docs/scenarios/brownfield-2.md), [`ambiguous.md`](docs/scenarios/ambiguous.md)
