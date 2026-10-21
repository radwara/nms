# AGENTS.md

Instructions for AI coding agents in this repo. Humans start at `README.md`.

## Sources of truth

- `docs/requirements.md` - requirement IDs (FR-*, NFR-*, SC-*)
- `docs/ambiguity-log.md` - accepted assumptions (A-*); only `Accepted` entries are binding
- `docs/architecture.md`, `docs/adr/` - design and decisions
- `docs/process.md` - workflow and definition of done

## Ground rules

- The engineer owns every decision. Propose, do not decide. If something is not covered by a requirement, accepted assumption, or ADR, stop and ask.
- Work only on the assigned issue. No drive-by refactors or scope additions; suggest a new issue instead.
- Prefer the simplest design that meets the requirement. No new dependency or infrastructure without an ADR.
- Never put message content, contact details, secrets, or tokens in logs, audit records, or commit/PR text.
- Never weaken or delete a test to make it pass. If a test looks wrong, say why.
- Report outcomes as they are: failing tests, skipped steps, and uncertainty are stated, not smoothed over.
- When a discussion reaches a decision, record the outcome (not a transcript), including who raised what:
  design question -> `design` issue closed by an ADR; spec clarification -> `ambiguity-log.md` row;
  change to existing code -> `enhancement` or `bug` issue.
- Commit messages: one concise line, no body, no trailers, no mention of AI tools or vendors. AI involvement is disclosed only in the PR's AI-usage note.

## Workflow

```
issue -> branch <type>/<issue>-<slug> -> tests from acceptance criteria -> implement
  -> ./mvnw verify -> independent review -> PR -> CI green -> merge
```

- Branch types: `feat`, `fix`, `refactor`, `docs`, `chore`
- Commits: one-line Conventional Commit referencing the issue, e.g. `feat(routing): severity escalation (#12)`
- PR body: `Closes #n`, requirement/assumption IDs, test evidence, AI-usage note (generated / changed / rejected / why)

## When acting as reviewer

- Review against the issue's acceptance criteria and referenced IDs, not personal taste.
- Report concrete defects with a failure scenario and severity. Skip style nits unless they hide a bug.

## Build and test

```
./mvnw verify                 # format check, compile (-Werror), unit + integration tests, coverage
./mvnw spotless:apply         # fix formatting
./mvnw spring-boot:run        # run on :8080 (in-memory H2)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # file H2 + console at /h2-console
```

- Unit tests: `*Test` (surefire). Integration tests: `*IT` (failsafe, full Spring context).
- Coverage report: `target/site/jacoco/index.html`.
- Tests that need metrics endpoints use `@AutoConfigureMetrics` (Spring Boot disables metrics export in tests).
