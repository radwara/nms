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
- Write-up: PR #43. Code: this branch. `ChannelSelection.decide` takes the severity; for `CRITICAL` an opt-out no longer makes a channel unusable, and an opted-out channel that is selected gets `RoutingReason.OPT_OUT_OVERRIDDEN_CRITICAL`. No schema change (`routing_reason` is `varchar(32)`, no check constraint), no config.
- Will change (opt-out-wins cases for CRITICAL): `ChannelSelectionTest` (escalation withheld when opted out; removed candidate reported as withheld escalation), `RoutingIT` (escalation withheld when opted out of SMS; multi-recipient case), `SubmitNotificationIT` (delivery count 4 -> 5; found by the build, approved by the engineer), demo step 3, README excerpt, `ChannelSelection` Javadoc, architecture.md Routing steps 2-3.
- Unchanged on purpose: INFO / WARNING opt-out tests (guard that opt-out still applies).

## 3. Validation

| Branch | Tests |
|---|---|
| CRITICAL + opt-out, requested channel | `ChannelSelectionTest#criticalRequestedChannelOverridesOptOut`, `#criticalOverriddenCandidateIsNotEscalatedAgain`; `RoutingIT#criticalRequestedChannelOverridesOptOut` |
| CRITICAL + opt-out, all-channels default | `ChannelSelectionTest#criticalDefaultChannelsOverrideOptOut`; `RoutingIT#criticalWithoutChannelsOverridesOptOut`, `#seededRecipientsRouteAsDocumented` |
| CRITICAL + opt-out, escalation | `ChannelSelectionTest#criticalEscalationOverridesOptOut`; `RoutingIT#criticalEscalationOverridesSmsOptOut` |
| CRITICAL + opt-out + no address -> `NO_ADDRESS` | `ChannelSelectionTest#criticalOptedOutChannelWithoutAddressIsRemovedAsNoAddress`; `RoutingIT#criticalOptedOutChannelWithoutAddressIsRemovedAsNoAddress` |
| INFO / WARNING + opt-out still removed | `ChannelSelectionTest#belowCriticalOptOutRemovesCandidatesAndWithholdsEscalation` (both severities); `RoutingIT#warningOptedOutChannelIsStillRemoved` and the unchanged INFO opt-out tests; `RoutingPolicyIT#warningEscalationIsStillWithheldWhenOptedOut` |

ITs assert the full audit history: an overridden channel is one `ROUTED <channel> OPT_OUT_OVERRIDDEN_CRITICAL`, no `ROUTE_REMOVED`.

- Flipped: `ChannelSelectionTest#escalationIsWithheldWhenOptedOut` -> `#criticalEscalationOverridesOptOut`; `#removedCandidateIsAlsoReportedAsAWithheldEscalation` -> `#criticalOverriddenCandidateIsNotEscalatedAgain` (old assertion kept for INFO / WARNING in `#belowCriticalOptOutRemovesCandidatesAndWithholdsEscalation`); `RoutingIT#escalationIsWithheldWhenOptedOutOfSms` -> `#criticalEscalationOverridesSmsOptOut`; `RoutingIT#seededRecipientsRouteAsDocumented`; `SubmitNotificationIT#validRequestIs202WithOneNotificationPerRecipientEachAuditedAccepted`.
- Severity argument added, assertions unchanged (`ChannelSelectionTest`; `decide` now takes the severity):
  - `WARNING`: `escalationListedTwiceIsDecidedOnce`. It has an opt-out, so it still expects the escalation to be withheld.
  - `INFO`: `requestedChannelsAreTheCandidatesInRequestOrder`, `withoutRequestedChannelsTheEnabledChannelsAreUsed`, `withoutRequestedChannelsAnOptedOutChannelIsRemoved`, `withoutRequestedChannelsEveryOptOutIsRemoved`, `optedOutRequestedChannelIsRemoved`, `channelWithoutAddressIsRemoved`, `optOutIsReportedBeforeAMissingAddress`.
  - `CRITICAL` (no opt-out, so the override does not apply): `escalationAddsItsChannelAfterTheCandidates`, `escalationIsWithheldWithoutAnAddress`, `channelBothRequestedAndEscalatedKeepsRequested`, `channelFromPreferencesAndEscalatedKeepsPreference`, `nothingSelectedWhenNoChannelIsUsable`.
- Demo step 3, `user-sms-optout` (CRITICAL, no channels requested): deliveries `EMAIL`, `SMS`; audit `ROUTED EMAIL: PREFERENCE`, `ROUTED SMS: OPT_OUT_OVERRIDDEN_CRITICAL`, no `ROUTE_REMOVED`.
