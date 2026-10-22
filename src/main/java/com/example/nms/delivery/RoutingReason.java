package com.example.nms.delivery;

/** Why a channel was selected for a delivery (architecture.md "Routing"). */
public enum RoutingReason {
  REQUESTED,
  PREFERENCE,
  SEVERITY_ESCALATION,
  /** CRITICAL routed a channel the recipient opted out of (A-30). */
  OPT_OUT_OVERRIDDEN_CRITICAL
}
