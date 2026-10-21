# Engineering Process

AI drafts within a task; the engineer approves every gate. No code is merged without an issue, a PR, green CI, and a review.

## Phases and gates

| Phase | Output | Gate (engineer) |
|---|---|---|
| 0. Frame | `requirements.md`, `ambiguity-log.md` | Assumptions accepted |
| 1. Design | `architecture.md`, ADRs in `docs/adr/` | Design approved before code |
| 2. Decompose | GitHub issues with acceptance criteria, linked to requirement IDs | Backlog approved |
| 3. Build | One branch + PR per issue | Definition of done met |
| 4. Validate | E2E demo script, traceability matrix | Milestone sign-off |

Brownfield starts with a written impact analysis of the tagged greenfield code. Ambiguous starts with options and questions before any code.

## Roles

| Role | Who |
|---|---|
| Engineer | Decides, approves gates, reviews and merges PRs |
| Orchestrator (AI session) | Plans the issue, briefs the implementer, checks output against acceptance criteria, opens the PR, runs reviewers, brings findings to the engineer |
| Implementer (AI subagent) | Writes code and tests for one issue on its branch; runs `./mvnw verify`; does not push |
| Reviewers (AI subagents, fresh context) | Bug, architecture, performance, and general reviews per PR |

## Per-issue loop

```
issue (acceptance criteria) -> branch -> tests from criteria -> AI implements
  -> local build -> independent AI review (fresh context) -> engineer review
  -> PR (with AI-usage note) -> CI green -> merge
```

## Independent review checkpoints

One independent review per PR by a fresh-context reviewer (separate AI session with no access to the implementing conversation), plus one-off reviews at planning points (design freeze, backlog). Findings and the engineer's disposition (accepted / rejected / deferred, with reason) are recorded on the PR.

Reviewers are chosen per PR:

| Reviewer | When |
|---|---|
| Bug finder | Every PR |
| Architecture review | PRs that set or change structure: scaffold, schema, API platform, worker, brownfield |
| Performance check | PRs with queries, polling, or hot paths: schema indexes, worker poll and claim, status and audit reads, dedup lookup |
| General review | PRs with heavy or cross-cutting acceptance criteria: checks intent against spec, issue, ADRs, and architecture |

Milestone PRs widen the focus:

| PR | Additional focus |
|---|---|
| Greenfield tag (#13) | Code vs `architecture.md` drift; spec coverage |
| Brownfield impact analysis (#14) | Missed layers, regression risk, test plan, before any code |
| Ambiguous options (#15) | Are the options and questions real; is the decision defensible |
| Final (#16) | Acts as the grader: runs the README from a fresh clone, checks every requirement |

## Checks

| Layer | Check |
|---|---|
| Before code | Acceptance criteria approved; tests written from them |
| Local | `./mvnw verify`; local git hooks enforce commit message format |
| CI | Format check, compiler warnings as errors, unit + integration tests, coverage report, secret scan, Docker image build and non-root check; demo script smoke run (from #13) |
| Dependencies | Dependabot for Maven and GitHub Actions |
| Review | Independent reviewer + engineer review on every PR |
| Traceability | `requirements.md` Test column filled per merged requirement |
| Scope | Hours logged per milestone against the budget; cut list from the design review applied when behind |

## Definition of done

- Acceptance criteria covered by tests the engineer has read
- CI green
- Independent review run; findings fixed or filed as issues
- PR has an AI-usage note: what AI generated, what was changed or rejected, why
- `requirements.md` test column updated

## Bugs

Any defect found in review, testing, or demo becomes a `bug` issue (repro, root cause), fixed in its own PR with a regression test. Label `found-by:review`, `found-by:test`, or `found-by:engineer`.

## Labels

`feature`, `enhancement`, `bug`, `design`, `docs`, `chore`, `found-by:*`. Milestones: `Design`, `Greenfield`, `Brownfield`, `Ambiguous`, `Wrap-up`.

## Recording decisions

| Discussion | Recorded as |
|---|---|
| Design question | `design` issue, closed by an ADR in `docs/adr/` |
| Spec clarification | Row in `ambiguity-log.md` |
| Change to existing code | `enhancement` or `bug` issue -> PR |

Record outcomes and who raised what, not transcripts.
