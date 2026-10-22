# Greenfield: notification service from an empty repo

- Issues: #6-#13 (milestone Greenfield). Requirements: SC-1, FR-1 - FR-6, NFR-1 - NFR-6, D-1.
- Before code: requirements and ambiguity log at bootstrap (`9d46fb9`); architecture and ADR-0001 - ADR-0005 merged via #3, #5; backlog reviewed independently and re-scoped before #6 ([backlog review](https://github.com/radwara/nms/issues/6#issuecomment-6308373952)).
- Result: submit -> route -> queue -> send with retries -> callback -> status and audit, runnable with `scripts/demo.sh`. Tag `v0.1.0-greenfield` after #13 merges.

## 1. Decomposition

Build order (backlog review): #6 -> #7 -> #12 -> #8 -> #9 -> #10 -> #11 -> #13. API platform (#12) moved ahead of the submit API so every endpoint inherits auth and the error contract.

| Issue | Delivered | Requirements |
|---|---|---|
| #6 Scaffold, CI, quality gates | Spring Boot 4 app, Spotless, `-Xlint:all -Werror`, JaCoCo, CI (secret scan, Docker non-root), Dependabot, PR template, actuator limited to health/info/prometheus | NFR-1, NFR-3, NFR-5, NFR-6 |
| #7 Schema, entities, seeded directory | Flyway V1 (six tables, constraints, indexes), V2 seed with the simulated-outcome address convention, entities by feature package | FR-1.2, FR-2.x, FR-6.1 |
| #12 API platform and security | Hashed API keys (rotation), webhook tokens (fail-closed), one error body and code catalog for every error path, trace ids, package rule (ArchUnit) | NFR-3, NFR-5 |
| #8 Submit API | Lenient binding + all-errors validation, per-recipient split, idempotency (canonical hash, replay, expired-key takeover, race-safe, 4092), `ACCEPTED` / `DUPLICATE_SUBMISSION` / `REJECTED` audit | FR-1, FR-4.1, FR-4.3, FR-4.4, FR-6 |
| #9 Routing | Requested or preferred channels, opt-out and no-address removal, configurable severity escalation (opt-out wins, A-30 baseline), every decision audited | FR-3.1, FR-6.1 |
| #10 Delivery worker | Per-(status, priority) poll, versioned claim committed before send, simulated providers with dedupe, retry policy, expiry, lease reclaim, metrics, per-attempt trace ids | FR-4.2, FR-5, FR-6.1, NFR-2, NFR-5 |
| #11 Status, audit, callbacks | Tenant-scoped status and audit APIs, derived `overallStatus`, single callback writer with row lock, simulated vendors calling back over HTTP | FR-2, FR-4.2, FR-4.3, FR-6.1 |
| #13 E2E, demo, this document | `GreenfieldE2EIT`, `scripts/demo.sh` in CI, traceability matrix filled | SC-1, D-1, D-3, D-5, FR-6.2 |

Review findings for later issues were folded into their bodies as "(added from #N review)" criteria, e.g. 12 carry-overs from the #7 reviews alone.

## 2. Execution

One branch and PR per issue; implementation by an implementer subagent from an orchestrator brief; fresh-context reviewers chosen per PR (`docs/process.md`); fixes applied before merge and re-verified. Each PR carries an AI-usage note and a review record comment.

| PR | Issue | Commits | Reviewers | Findings | Fixed | Other dispositions | Tests at merge (unit + IT) |
|---|---|---|---|---|---|---|---|
| [#17](https://github.com/radwara/nms/pull/17#issuecomment-6308516221) | #6 | 9 | Bug, architecture | 16 | 10 | 1 accepted as documented, 2 rejected, 3 deferred to #7 / #12 | 7 (total) |
| [#24](https://github.com/radwara/nms/pull/24#issuecomment-6308922147) | #7 | 22 | Bug, architecture, performance | 14 | 9 | 5 carried to #8, #10, #11, #14 | 11 + 26 |
| [#25](https://github.com/radwara/nms/pull/25#issuecomment-6320280442) | #12 | 22 | Bug, architecture, security | 11 | 9 | 1 rejected, 1 carried to #11 | 102 + 83 |
| [#26](https://github.com/radwara/nms/pull/26#issuecomment-6321645782) | #8 | 26 | Bug, general, performance | 11 | 10 | 1 carried to #9 / #14 | 127 + 140 |
| [#27](https://github.com/radwara/nms/pull/27#issuecomment-6325765783) | #9 | 10 | Bug, performance | 6 | 3 | 1 engineer decision, 2 declined | 153 + 165 |
| [#32](https://github.com/radwara/nms/pull/32#issuecomment-6328806265) | #10 | 19 | Bug, architecture, performance | 12 | 10 | 1 declined, 1 carried to #11 | 224 + 212 |
| [#34](https://github.com/radwara/nms/pull/34#issuecomment-6332101203) | #11 | 18 | Bug, general | 11 | 7 | 2 engineer decisions, 1 documented limitation, 1 carried to #14 | 270 + 245 |
| #13 | #13 | - | Pending; widened to code vs `architecture.md` drift and spec coverage | - | - | Record on the PR | 270 + 249 |

Total for #6-#11: 81 findings, 58 fixed before merge.

### Bugs

| Bug | Found by | Where | Root cause | Fix |
|---|---|---|---|---|
| [#28](https://github.com/radwara/nms/issues/28) Claim token fails on Linux | `found-by:review` (bug finder + architecture, both reproduced) | #24 | Timestamp equality as a concurrency token; nanosecond `Instant` vs microsecond column | `version` token; `Clock` truncated to micros. Fixed in #24 |
| [#29](https://github.com/radwara/nms/issues/29) Idempotency-Key in logs during races | `found-by:review` (bug finder, reproduced) | #26 | Hibernate JDBC error logger quotes SQL values | Logger off; race ITs assert the key is never logged. Fixed in #26 |
| [#30](https://github.com/radwara/nms/issues/30) Malformed path value -> 500, value logged | `found-by:review` (bug, architecture, security; real Tomcat) | #25 | Catch-all handler and Boot's `/error` outside the error contract | All framework errors mapped; 16 real-Tomcat tests. Fixed in #25 |
| [#33](https://github.com/radwara/nms/issues/33) Log-format test depends on test order | `found-by:test` (Linux CI) | #32 | Logback is JVM-global; a later context reconfigured it | Fresh context for log-format tests. Fixed in #32 |
| [#36](https://github.com/radwara/nms/issues/36) Opt-out silently dropped when no channels requested | `found-by:review` (bug finder) | #27 | Opted-out channels filtered from candidates before the removal audit | All channels are candidates; every skipped channel audited. Fixed in #27 |
| [#37](https://github.com/radwara/nms/issues/37) Poison rows stall the delivery queue | `found-by:review` (bug finder, probed; architecture) | #32 | Pre-claim failures left rows due at the top of every poll | Finished `FAILED` with a reason without a claim; other pre-claim errors postpone the row. Fixed in #32 |
| [#38](https://github.com/radwara/nms/issues/38) Unbounded retries when a provider throws or crashes | `found-by:review` (bug finder, probed; architecture) | #32 | Attempt budget checked only after a `Failed` result; reclaims resent unchecked | Exceptions become `TRANSIENT`; budget enforced before every claim. Fixed in #32 |
| [#39](https://github.com/radwara/nms/issues/39) Callback timestamp earlier than the `SENT` write it lands on | `found-by:review` (bug finder, probed) | #34 | Clock read before the conditional update | Lock the row, then read the clock. Fixed in #34 |
| [#40](https://github.com/radwara/nms/issues/40) Duplicate vendor message id returns 500 | `found-by:review` (bug finder, probed; general) | #34 | Unique violation on `provider_message_id` | Id copied only when unused; outcome applied, audited `message id conflict`, 200. Fixed in #34 |

Other caught-by-build issues (formatter on JDK 21, Boot 4 metrics module) are listed in the #17 AI-usage note.

## 3. Validation

| Layer | Evidence |
|---|---|
| Unit + integration tests | 270 unit + 249 integration, green (`./mvnw verify`); 96.2% line coverage locally (JaCoCo, unit + IT merged), shown in each CI run summary |
| End to end | `GreenfieldE2EIT`: real server, background poller, simulated vendors calling back over HTTP; HTTP API only. Happy path (`SENT` then `DELIVERED` per channel), retry (`flaky2`: 3 attempts, then delivered), duplicate (200 replay, `duplicateSubmissions` 1), bounce (`FAILED` / `BOUNCED`), status and audit read back |
| Leak check | Same test: every audit row and all captured logs of the run contain no subject, body, email, phone, API key, or Idempotency-Key |
| Demo | `scripts/demo.sh`: the same flows plus a 4001 and a 401, polling with a timeout; CI runs it against the built jar (dev profile) |
| CI gates | Spotless check, `-Werror`, unit + integration tests, JaCoCo artifact, gitleaks, Docker build and non-root check, demo smoke run |
| Mutation checks | Implementer-reported (#26), each reverted: four deliberate breaks (expiry filter, unique-violation handling, `REJECTED` transaction, check order), each caught by its test |
| Measured | #26: 100-recipient submit 0 entity loads, batched; #27: 801 inserts in 16 batches; #32: per-priority poll reads 6 rows vs 2,006, 1,000-delivery backlog drains in ~2 s |
| Traceability | `docs/requirements.md` Test column: every greenfield FR / NFR row filled, untestable ones say why |

## 4. Engineer decisions and changes

| Where | Decision |
|---|---|
| Backlog ([#6](https://github.com/radwara/nms/issues/6#issuecomment-6308373952)) | One independent review per PR, wider at milestone PRs; #12 built before #8; scenario doc and leak test moved to #13 |
| #17 ([AI-usage note](https://github.com/radwara/nms/pull/17)) | Reviewers chosen per PR (bug, architecture, performance, general); implementer subagent from #7; CI badge and test/coverage summary |
| #26 ([check order](https://github.com/radwara/nms/pull/26#discussion_r4339680007)) | Idempotency lookup before time-based 422s: a retry of an accepted request replays even after `expiresAt` |
| #26 ([409](https://github.com/radwara/nms/pull/26#discussion_r4339707709)) | Concurrent request on the same key -> 409 / 4092 with `Retry-After`: a client conflict, not a server error |
| #27 ([double audit](https://github.com/radwara/nms/pull/27#discussion_r4342566572)) | Keep both events when an escalation is withheld after an earlier removal, so each routing decision is recorded |
| #15 ([A-30](https://github.com/radwara/nms/issues/15#issuecomment-6327907571)) | CRITICAL will override opt-out in the ambiguous scenario; greenfield keeps the opt-out-wins baseline until then |
| #32 ([retry policy](https://github.com/radwara/nms/pull/32#discussion_r4343946188)) | 3 attempts, 500 ms base, equal jitter, `Retry-After` cap 60 s, never retry 4xx; no default TTL (delivery is async) |
| #34 ([A-24, A-47 - A-49](https://github.com/radwara/nms/pull/34#discussion_r4344290584)) | `NOT_ROUTED` before `SCHEDULED`; callbacks apply to `RETRY_SCHEDULED` (retry cancelled), not `PENDING`; `EXPIRED` stays final, late callbacks audited; `CALLBACK_FAILED` renamed `PROVIDER_FAILED` |

## 5. Limitations carried forward

| Limitation | Where it goes |
|---|---|
| A different `Idempotency-Key` for the same event and message notifies the user again | #14 (brownfield: event-level dedup) |
| Opt-out wins over a CRITICAL escalation (A-30 baseline) | #15 (ambiguous) |
| Single instance, single-threaded poller, H2; scaling path documented, not built | architecture.md "Scaling path" |
| `SENT` without a callback stays `SENT`; simulated callbacks are one attempt, no retry | By design (ADR-0003) |
| A delivery that expired after a lease reclaim may have been sent; a late callback is audited, not applied (A-48) | Documented |
| Two concurrent callbacks for different deliveries with the same new `providerMessageId` -> 500 (vendor retries) | Documented (#34 review) |
| Content stored in plaintext; no purge for idempotency rows or audit | Production path (architecture.md) |
| `/actuator/prometheus` needs no key in the prototype | Internal management port in production |
| Real vendor payloads and signatures not mapped: `api.CallbackRequestValidator` parses the one generic format, `DeliveryCallbacks` maps event to status | Production: a per-provider `CallbackParser` chosen by `WebhookProvider` (not built) |
| Docker image built and checked in CI only | README (#16) |
