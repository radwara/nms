package com.example.nms.intake;

import java.time.Instant;
import java.util.List;

/**
 * Routes the new notifications of one submission to their deliveries. Called once per submission
 * inside the submit transaction, after the notifications are flushed and their {@code ACCEPTED}
 * audit events are written, so a failure rolls back the whole submission. Calls {@link
 * Notification#markRouted()} on every notification that gets a delivery: event dedup anchors only
 * on routed originals (ADR-0006).
 */
public interface RoutingStep {

  /** {@code notifications} are in request order. */
  void route(List<Notification> notifications, Instant now);
}
