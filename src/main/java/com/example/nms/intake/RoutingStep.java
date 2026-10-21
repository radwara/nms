package com.example.nms.intake;

import java.time.Instant;

/**
 * Routes one new notification to its deliveries. Called once per notification inside the submit
 * transaction, after the notification is flushed, so a failure rolls back the whole submission.
 */
public interface RoutingStep {

  void route(Notification notification, Instant now);
}
