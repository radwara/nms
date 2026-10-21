# ADR-0005: Technology stack

- Status: Accepted (2026-10-20)

## Context

The role centers on Java and Spring Boot. Reviewers must be able to run the prototype with one command and no installed infrastructure.

## Options considered

| Concern | Chosen | Rejected | Why rejected |
|---|---|---|---|
| Build | Maven | Gradle | Either works; Maven is the more common default for Spring reviewers |
| Database | H2 | PostgreSQL + Testcontainers | Needs Docker, not installed locally; reviewers would need it too |
| Persistence | JPA | Plain JDBC | JPA gives `@Version` and less mapping code; claims use native conditional updates anyway |

## Decision

| Concern | Choice | Why |
|---|---|---|
| Language | Java 21 | Current LTS; records, pattern matching |
| Framework | Spring Boot (current GA, pinned in `pom.xml`) | Web, validation, security, data, actuator, scheduling in one stack |
| Build | Maven with wrapper (`./mvnw`) | No local Maven install needed |
| Database | H2 (file-backed locally, in-memory in tests), PostgreSQL compatibility mode, UTC timestamps | Zero setup; PostgreSQL is the production path |
| Schema | Flyway migrations | Versioned, reviewable schema changes |
| Persistence | Spring Data JPA | Optimistic locking via `@Version` |
| Metrics | Micrometer + Prometheus registry | Standard Spring Boot observability |
| Code quality | Spotless (formatter check), `-Xlint:all -Werror` | Formatting and warnings enforced in CI |
| Logging | Spring Boot structured logging | JSON logs with one property |
| Tests | JUnit 5, Spring Boot Test, MockMvc, Awaitility | Unit, slice, and end-to-end tests for async flows |
| CI | GitHub Actions: format check, build, tests, JaCoCo report artifact, Docker image build (non-root user); Dependabot for Maven and Actions | Docker is not installed locally; CI verifies the Dockerfile |

Single module, packaged by feature (`api`, `intake`, `routing`, `delivery`, `provider`, `callback`, `status`, `audit`, `directory`, `security`), plus `common`: shared vocabulary enums (and their stored codes) only, no business logic.

## Consequences

- Plus: one-command run (`./mvnw spring-boot:run`); familiar stack for reviewers.
- Minus: H2 differs from PostgreSQL in locking behavior; documented as a limitation.
