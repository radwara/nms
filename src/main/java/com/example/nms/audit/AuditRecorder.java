package com.example.nms.audit;

import com.example.nms.common.Channel;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit events. {@code detail} holds codes and reasons only, never content, contact details,
 * or submitted values.
 */
@Component
public class AuditRecorder {

  static final int MAX_DETAIL_LENGTH = 256;
  static final String DUPLICATE_DETAIL = "same Idempotency-Key; original returned";
  static final String NOT_ROUTED_DETAIL = "no usable channel";

  private final AuditEventRepository events;
  private final Clock clock;

  AuditRecorder(AuditEventRepository events, Clock clock) {
    this.events = events;
    this.clock = clock;
  }

  /** Part of the submit transaction: the event commits with the notification. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void accepted(String sourceSystem, UUID notificationId, Instant at) {
    events.save(
        new AuditEvent(
            sourceSystem, notificationId, null, AuditEventType.ACCEPTED, null, null, at));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void duplicateSubmission(String sourceSystem, UUID notificationId, Instant at) {
    events.save(
        new AuditEvent(
            sourceSystem,
            notificationId,
            null,
            AuditEventType.DUPLICATE_SUBMISSION,
            null,
            DUPLICATE_DETAIL,
            at));
  }

  /** A channel selected for a delivery; {@code reason} is the routing reason code. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void routed(
      String sourceSystem,
      UUID notificationId,
      UUID deliveryId,
      Channel channel,
      String reason,
      Instant at) {
    events.save(
        new AuditEvent(
            sourceSystem, notificationId, deliveryId, AuditEventType.ROUTED, channel, reason, at));
  }

  /** A channel removed or withheld by routing; {@code reason} is a code, e.g. {@code OPTED_OUT}. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void routeRemoved(
      String sourceSystem, UUID notificationId, Channel channel, String reason, Instant at) {
    events.save(
        new AuditEvent(
            sourceSystem, notificationId, null, AuditEventType.ROUTE_REMOVED, channel, reason, at));
  }

  /** No channel remained, so the notification has no deliveries. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void notRouted(String sourceSystem, UUID notificationId, Instant at) {
    events.save(
        new AuditEvent(
            sourceSystem,
            notificationId,
            null,
            AuditEventType.NOT_ROUTED,
            null,
            NOT_ROUTED_DETAIL,
            at));
  }

  /** A delivery written to the queue, due at its {@code next_attempt_at}. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void queued(
      String sourceSystem, UUID notificationId, UUID deliveryId, Channel channel, Instant at) {
    events.save(
        new AuditEvent(
            sourceSystem, notificationId, deliveryId, AuditEventType.QUEUED, channel, null, at));
  }

  /**
   * A rejected request (no notification exists). Own transaction, because the request's work, if
   * any, rolled back. {@code detail} lists the distinct error codes and the trace id, so the event
   * can be matched to the request's logs, e.g. {@code codes 4004,4003; trace 8f3c...}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void rejected(String sourceSystem, Collection<Integer> errorCodes, String traceId) {
    String trace = traceId == null ? "" : "; trace " + traceId;
    String codes =
        errorCodes.stream()
            .distinct()
            .map(String::valueOf)
            .collect(Collectors.joining(",", "codes ", ""));
    events.save(
        new AuditEvent(
            sourceSystem,
            null,
            null,
            AuditEventType.REJECTED,
            null,
            truncate(codes, MAX_DETAIL_LENGTH - trace.length()) + trace,
            clock.instant().truncatedTo(ChronoUnit.MICROS)));
  }

  private static String truncate(String text, int maxLength) {
    return text.length() <= maxLength ? text : text.substring(0, maxLength);
  }
}
