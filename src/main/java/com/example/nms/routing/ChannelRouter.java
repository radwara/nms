package com.example.nms.routing;

import com.example.nms.audit.AuditRecorder;
import com.example.nms.common.Channel;
import com.example.nms.delivery.Delivery;
import com.example.nms.delivery.DeliveryRepository;
import com.example.nms.directory.AddressPresence;
import com.example.nms.directory.ChannelPreferenceId;
import com.example.nms.directory.ChannelPreferenceRepository;
import com.example.nms.directory.RecipientRepository;
import com.example.nms.intake.Notification;
import com.example.nms.intake.RoutingStep;
import com.example.nms.routing.ChannelSelection.Decision;
import com.example.nms.routing.ChannelSelection.Removed;
import com.example.nms.routing.ChannelSelection.Selected;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns each new notification into {@code PENDING} deliveries, one per selected channel, inside the
 * submit transaction. Reads the directory with one address query and one opt-out query per
 * submission; makes no provider calls. Audits every decision, then {@code QUEUED} per delivery.
 */
@Component
class ChannelRouter implements RoutingStep {

  private final RecipientRepository recipients;
  private final ChannelPreferenceRepository preferences;
  private final DeliveryRepository deliveries;
  private final AuditRecorder audit;
  private final RoutingProperties policy;

  ChannelRouter(
      RecipientRepository recipients,
      ChannelPreferenceRepository preferences,
      DeliveryRepository deliveries,
      AuditRecorder audit,
      RoutingProperties policy) {
    this.recipients = recipients;
    this.preferences = preferences;
    this.deliveries = deliveries;
    this.audit = audit;
    this.policy = policy;
  }

  @Override
  public void route(List<Notification> notifications, Instant now) {
    if (notifications.isEmpty()) {
      return;
    }
    Set<String> ids =
        notifications.stream().map(Notification::getRecipientId).collect(Collectors.toSet());
    Map<String, AddressPresence> addresses =
        recipients.findAddressPresence(ids).stream()
            .collect(Collectors.toMap(AddressPresence::recipientId, Function.identity()));
    Map<String, Set<Channel>> optOuts =
        preferences.findOptOuts(ids).stream()
            .collect(
                Collectors.groupingBy(
                    ChannelPreferenceId::recipientId,
                    Collectors.mapping(
                        ChannelPreferenceId::channel,
                        Collectors.toCollection(() -> EnumSet.noneOf(Channel.class)))));

    List<Delivery> created = new ArrayList<>();
    for (Notification notification : notifications) {
      AddressPresence recipient = addresses.get(notification.getRecipientId());
      if (recipient == null) {
        // Intake checked existence in this transaction; the directory is read-only.
        throw new IllegalStateException(
            "Recipient of notification " + notification.getId() + " is not in the directory");
      }
      List<Decision> decisions =
          ChannelSelection.decide(
              notification.getSeverity(),
              notification.getRequestedChannels(),
              policy.escalation(notification.getSeverity()),
              optOuts.getOrDefault(notification.getRecipientId(), Set.of()),
              recipient);
      created.addAll(route(notification, decisions, now));
    }
    deliveries.saveAll(created);
  }

  private List<Delivery> route(Notification notification, List<Decision> decisions, Instant now) {
    String source = notification.getSourceSystem();
    UUID notificationId = notification.getId();
    List<Delivery> routed = new ArrayList<>();
    for (Decision decision : decisions) {
      switch (decision) {
        case Removed removed ->
            audit.routeRemoved(source, notificationId, removed.channel(), removed.reason(), now);
        case Selected selected -> {
          Delivery delivery =
              new Delivery(
                  UUID.randomUUID(),
                  notificationId,
                  selected.channel(),
                  policy.provider(selected.channel()),
                  notification.getPriority(),
                  selected.reason(),
                  dueAt(notification, now),
                  now);
          routed.add(delivery);
          audit.routed(
              source,
              notificationId,
              delivery.getId(),
              delivery.getChannel(),
              selected.reason().name(),
              now);
        }
      }
    }
    if (routed.isEmpty()) {
      audit.notRouted(source, notificationId, now);
    } else {
      notification.markRouted();
    }
    routed.forEach(d -> audit.queued(source, notificationId, d.getId(), d.getChannel(), now));
    return routed;
  }

  /** {@code max(now, scheduledAt)}: a past or absent schedule is due now. */
  private static Instant dueAt(Notification notification, Instant now) {
    Instant scheduledAt = notification.getScheduledAt();
    return scheduledAt != null && scheduledAt.isAfter(now) ? scheduledAt : now;
  }
}
