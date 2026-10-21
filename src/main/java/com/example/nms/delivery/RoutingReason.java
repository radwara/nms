package com.example.nms.delivery;

/** Why a channel was selected for a delivery (architecture.md "Routing"). */
public enum RoutingReason {
  REQUESTED,
  PREFERENCE,
  SEVERITY_ESCALATION
}
