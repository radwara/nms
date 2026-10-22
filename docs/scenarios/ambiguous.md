# Ambiguous requirement: CRITICAL alerts vs channel opt-out

- Issue: #15. Requirements: SC-3, A-30, A-02 (spec 3.3 has no content; scenario chosen by us).

## 1. Decomposition

### Ambiguity
Spec 4.3 lists routing factors (requested channel, severity, recipient preferences, routing policy) with no precedence. Opt-out is stored per channel, without a message category, so it cannot say whether it covers alerts. Example: a CRITICAL fraud alert for a user who opted out of SMS.

### Questions for stakeholders (positions assumed until confirmed)
| Stakeholder | Question | Assumed answer |
|---|---|---|
| Compliance / legal | Does an opt-out cover security alerts and OTPs? | No; opt-out covers commercial messages |
| Product | What is CRITICAL? | Must-deliver: OTP, fraud, security. Promotions respect opt-out |
| Source-system owners | Will CRITICAL be used only for must-deliver alerts? | Yes; promotions are INFO / WARNING |

### Options
| Option | Trade-off |
|---|---|
| A. Opt-out always wins (greenfield baseline) | Respects every preference; critical alerts can be lost |
| **B. CRITICAL ignores opt-out** | Critical alerts reach the user; opt-out still governs INFO / WARNING |
| C. Consent per message category (opt out of marketing, not alerts) | Right long-term model; needs a preference schema change, out of scope |

### Decision
**B. For CRITICAL, opt-out never removes a channel.** Trust CRITICAL.
- Opt-out is for commercial messages (promotions, ads), not OTPs or critical alerts.
- Channel selection is otherwise unchanged: requested channels, else all channels, plus severity escalation. A missing address still removes a channel.
- Audit: an overridden channel is logged once as `ROUTED <channel>` with reason `OPT_OUT_OVERRIDDEN_CRITICAL`.
- Fixed rule, not configuration: it is a safety rule; a config switch could silently turn it off.

### Limitations
- Carrier-level SMS STOP is not modeled; real providers block even critical messages after STOP.
- Callers set severity; a misused CRITICAL reaches every opted-out channel. Production: restrict CRITICAL per source system.
- Assumed answers above are unconfirmed (A-30). The rule lives in one place, so it is easy to reverse.

## 2. Execution
- Write-up: PR #43. Code: to follow.
- Will change (opt-out-wins cases for CRITICAL): `ChannelSelectionTest` (escalation withheld when opted out; removed candidate reported as withheld escalation), `RoutingIT` (escalation withheld when opted out of SMS; multi-recipient case), demo step 3, README excerpt, `ChannelSelection` Javadoc, architecture.md Routing step 3.
- Unchanged on purpose: INFO / WARNING opt-out tests (guard that opt-out still applies).

## 3. Validation
<!-- filled after the code: tests per branch, demo step 3 output -->
