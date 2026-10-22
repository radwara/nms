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

## Run the demo

Needs `curl` and `jq`. The `dev` profile turns on the delivery worker, the simulated vendors' callbacks, and the documented demo credentials (ADR-0001).

```
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # terminal 1; H2 file database in ./data (NMS_DATA_DIR: absolute path)
scripts/demo.sh                                         # terminal 2; NMS_BASE_URL, NMS_API_KEY, DEMO_TIMEOUT override the defaults
```

Seven steps against the HTTP API: health, happy path, retry, duplicate submission, bounce, a validation error, an authentication error. Each step polls status until final (30 s timeout) and the script exits non-zero on any unexpected result. CI runs it against the built jar. Excerpt:

```
== 3. Retry path: user-flaky2, two transient failures (503), then accepted and delivered
   POST /api/v1/notifications  key=demo-1792641124-26102-user-flaky2  -> 202  user-flaky2 ACCEPTED 072d3b15-...
   GET  /api/v1/notifications/072d3b15-...  -> 200  overallStatus=DELIVERED  duplicateSubmissions=0
        EMAIL  DELIVERED  attempts=3
        SMS  DELIVERED  attempts=3
   GET  /api/v1/notifications/072d3b15-.../audit  -> 200
        ACCEPTED ROUTED(EMAIL) ROUTED(SMS) QUEUED(EMAIL) QUEUED(SMS) ATTEMPT_FAILED(SMS)
        RETRY_SCHEDULED(SMS) ATTEMPT_FAILED(EMAIL) RETRY_SCHEDULED(EMAIL) ... SENT(EMAIL) DELIVERED(SMS) DELIVERED(EMAIL)
   OK

== 5. Bounce: user-bounce, email accepted, then bounced by vendor callback
   ...
        EMAIL  FAILED  attempts=1  reason=BOUNCED
   OK

== 6. Validation error: submit without Idempotency-Key
   POST /api/v1/notifications  (no Idempotency-Key)  -> 400  [{"code":4001,"field":"Idempotency-Key","message":"Required header is missing"}]
   OK
...
Demo passed: 7 steps.
```

## Docs

- Architecture: [`docs/architecture.md`](docs/architecture.md)
- Decisions: [`docs/adr/`](docs/adr/)
- Requirements and assumptions: [`docs/requirements.md`](docs/requirements.md), [`docs/ambiguity-log.md`](docs/ambiguity-log.md)
- Process: [`docs/process.md`](docs/process.md)
- Scenarios: [`docs/scenarios/greenfield.md`](docs/scenarios/greenfield.md), [`docs/scenarios/brownfield.md`](docs/scenarios/brownfield.md)

Full README (setup, reviewer guide, limitations) lands with #16.
