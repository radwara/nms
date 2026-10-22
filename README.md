# Notification Management Service

[![CI](https://github.com/radwara/nms/actions/workflows/ci.yml/badge.svg)](https://github.com/radwara/nms/actions/workflows/ci.yml)

Prototype service that accepts notification requests from source systems, routes each recipient to channels, delivers asynchronously through providers, and tracks status through provider callbacks.

Each CI run's summary page shows test counts and line coverage.

## Quick start

Requires Java 21. No other installs.

```
./mvnw verify            # format check, build, unit + integration tests, coverage
./mvnw spring-boot:run   # http://localhost:8080/actuator/health
```

Event dedup is on by default (`NMS_DEDUP_ENABLED`, `NMS_DEDUP_WINDOW`, default `24h`; ADR-0006). Without a profile no API keys are configured (`NMS_API_KEYS` is empty), so every `/api` call returns 401. Use the `dev` profile below to try the API.

## Run the demo

Needs `curl` and `jq`. The delivery worker is on by default; the `dev` profile adds the simulated vendors' callbacks, the documented demo credentials (ADR-0001), the H2 console, and plain-text logs.

```
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # terminal 1; H2 file database in ./data (NMS_DATA_DIR: absolute path)
scripts/demo.sh                                         # terminal 2; NMS_BASE_URL, NMS_API_KEY, DEMO_TIMEOUT override the defaults
```

Nine steps against the HTTP API: health, happy path, routing (requested channel, severity escalation, opt-out), retry, duplicate submission, event dedup (same event and message under a new key is `SUPPRESSED`; a new message for the same event is delivered), bounce, a validation error, an authentication error. Steps 2-7 poll status until final (30 s timeout); each step uses its own `eventId`, and step 6 reuses it on purpose. The script exits non-zero on any unexpected result. CI runs it against the built jar. Excerpt:

```
== 3. Routing: CRITICAL adds SMS; an opt-out withholds it (opt-out wins until #15)
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
   GET  /api/v1/notifications/059815ec-.../audit  -> 200
        ACCEPTED ROUTED(EMAIL) ROUTE_REMOVED(SMS) ROUTE_REMOVED(SMS) QUEUED(EMAIL) SENT(EMAIL)
        DELIVERED(EMAIL)
        routing: ROUTED EMAIL: PREFERENCE
        routing: ROUTE_REMOVED SMS: OPTED_OUT
        routing: ROUTE_REMOVED SMS: SEVERITY_ESCALATION withheld: OPTED_OUT
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

## Docs

- Architecture: [`docs/architecture.md`](docs/architecture.md)
- Decisions: [`docs/adr/`](docs/adr/)
- Requirements and assumptions: [`docs/requirements.md`](docs/requirements.md), [`docs/ambiguity-log.md`](docs/ambiguity-log.md)
- Process: [`docs/process.md`](docs/process.md)
- Scenarios: [`docs/scenarios/greenfield.md`](docs/scenarios/greenfield.md), [`docs/scenarios/brownfield.md`](docs/scenarios/brownfield.md)

Full README (setup, reviewer guide, limitations) lands with #16.
