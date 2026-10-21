# ADR-0005: Technology stack

- Status: Proposed (2026-10-20)

## Context

The role centers on Java and Spring Boot. Reviewers must be able to run the prototype with one command and no installed infrastructure.

## Decision

| Concern | Choice | Why |
|---|---|---|
| Language | Java 21 | Current LTS; records, pattern matching |
| Framework | Spring Boot (current GA, pinned in `pom.xml`) | Web, validation, security, data, actuator, scheduling in one stack |
| Build | Maven with wrapper (`./mvnw`) | No local Maven install needed |
| Database | H2 (file-backed locally, in-memory in tests) | Zero setup; PostgreSQL is the production path |
| Schema | Flyway migrations | Versioned, reviewable schema changes |
| Persistence | Spring Data JPA | Optimistic locking via `@Version` |
| Metrics | Micrometer + Prometheus registry | Standard Spring Boot observability |
| Tests | JUnit 5, Spring Boot Test, MockMvc, Awaitility | Unit, slice, and end-to-end tests for async flows |
| CI | GitHub Actions: build, tests, coverage report, Docker image build | Docker is not installed locally; CI verifies the Dockerfile |

Single module, packaged by feature (`api`, `intake`, `routing`, `delivery`, `provider`, `callback`, `status`, `audit`, `directory`, `security`).

## Consequences

- Plus: one-command run (`./mvnw spring-boot:run`); familiar stack for reviewers.
- Minus: H2 differs from PostgreSQL in locking behavior; documented as a limitation.
