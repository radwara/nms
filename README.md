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

## Docs

- Architecture: [`docs/architecture.md`](docs/architecture.md)
- Decisions: [`docs/adr/`](docs/adr/)
- Requirements and assumptions: [`docs/requirements.md`](docs/requirements.md), [`docs/ambiguity-log.md`](docs/ambiguity-log.md)
- Process: [`docs/process.md`](docs/process.md)

Full README (setup, demo, reviewer guide, limitations) lands with #16.
