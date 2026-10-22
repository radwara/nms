# Ambiguous requirement: CRITICAL alerts vs channel opt-out

## 1. Ambiguity
Spec 4.3 routes by requested channel, severity, recipient preferences, and routing policy, but does not say which wins when they conflict. Example: a CRITICAL fraud alert for a user who opted out of SMS.

Opt-out is generally for commercial messages (promotions, ads). It is not meant for critical alerts (OTPs, fraud and security alerts). Respecting it for those means a user misses an alert they need.

## 2. Stakeholders
| Stakeholder | Position |
|---|---|
| Compliance / legal | Opt-out covers commercial messages; critical service messages override it |
| Product | CRITICAL means must-deliver (OTP, fraud, security); promotional messages always respect opt-out |
| Source-system owners | Send CRITICAL only for must-deliver alerts; promotions are INFO/WARNING |

## 3. Options
| Option | Trade-off |
|---|---|
| A. Opt-out always wins (greenfield baseline) | Respects every preference; critical alerts can be lost |
| **B. CRITICAL overrides opt-out** | Critical alerts reach the user; opt-out still governs everything else |
| C. CRITICAL falls back to another channel | Avoids the opted-out channel, but fails when it is the only usable one |

## 4. Decision
**B.** CRITICAL overrides channel opt-out. INFO and WARNING respect it. A missing address always removes the channel. Every override is audited. Fixed rule, not configuration.

## 5. Limitations
- Carrier-level SMS STOP is not modeled; real providers block even critical messages after STOP.
- Callers set severity, so CRITICAL could be misused. Production: restrict CRITICAL per source system.

## 6. Execution and validation
<!-- filled after the code: PR, tests, demo step -->
