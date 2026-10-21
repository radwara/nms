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

## Per-issue loop

```
issue (acceptance criteria) -> branch -> tests from criteria -> AI implements
  -> local build -> independent AI review (fresh context) -> engineer review
  -> PR (with AI-usage note) -> CI green -> merge
```

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
