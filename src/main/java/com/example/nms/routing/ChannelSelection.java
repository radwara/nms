package com.example.nms.routing;

import com.example.nms.common.Channel;
import com.example.nms.common.Severity;
import com.example.nms.delivery.RoutingReason;
import com.example.nms.directory.AddressPresence;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Channel decisions for one notification (architecture.md "Routing", A-25, A-30), in audit order:
 * candidates in request order (or channel order for preferences), then severity escalations.
 *
 * <p>Candidates are the requested channels, else every channel. A candidate the recipient opted out
 * of or has no address for is removed, so without a request the removals explain every channel the
 * recipient does not get. Escalation adds a channel unless the recipient opted out of it or has no
 * address for it. A channel already selected keeps its first reason.
 *
 * <p>For {@code CRITICAL}, an opt-out never removes a channel (A-30, docs/scenarios/ambiguous.md):
 * a candidate or escalation the recipient opted out of is selected with {@link
 * RoutingReason#OPT_OUT_OVERRIDDEN_CRITICAL}. A missing address still removes it. Fixed rule, not
 * configuration.
 */
final class ChannelSelection {

  static final String OPTED_OUT = "OPTED_OUT";
  static final String NO_ADDRESS = "NO_ADDRESS";
  static final String ESCALATION_WITHHELD = RoutingReason.SEVERITY_ESCALATION + " withheld: ";

  private ChannelSelection() {}

  sealed interface Decision permits Selected, Removed {
    Channel channel();
  }

  /** The channel gets a delivery. */
  record Selected(Channel channel, RoutingReason reason) implements Decision {}

  /** The channel was removed or withheld; {@code reason} is the audited code. */
  record Removed(Channel channel, String reason) implements Decision {}

  /**
   * @param severity the notification's severity; {@code CRITICAL} overrides opt-outs
   * @param requested channels from the request, or {@code null} to use preferences
   * @param escalation channels the policy adds for the notification's severity
   */
  static List<Decision> decide(
      Severity severity,
      List<Channel> requested,
      List<Channel> escalation,
      Set<Channel> optedOut,
      AddressPresence addresses) {
    boolean critical = severity == Severity.CRITICAL;
    List<Decision> decisions = new ArrayList<>();
    Set<Channel> selected = EnumSet.noneOf(Channel.class);

    List<Channel> candidates = requested != null ? requested : Arrays.asList(Channel.values());
    RoutingReason reason = requested != null ? RoutingReason.REQUESTED : RoutingReason.PREFERENCE;
    for (Channel channel : new LinkedHashSet<>(candidates)) {
      String unusable = unusable(channel, critical, optedOut, addresses);
      if (unusable != null) {
        decisions.add(new Removed(channel, unusable));
      } else {
        selected.add(channel);
        decisions.add(new Selected(channel, selectedReason(channel, reason, critical, optedOut)));
      }
    }

    for (Channel channel : new LinkedHashSet<>(escalation)) {
      if (selected.contains(channel)) {
        continue; // already routed; keeps its first reason
      }
      String unusable = unusable(channel, critical, optedOut, addresses);
      if (unusable != null) {
        decisions.add(new Removed(channel, ESCALATION_WITHHELD + unusable));
      } else {
        selected.add(channel);
        decisions.add(
            new Selected(
                channel,
                selectedReason(channel, RoutingReason.SEVERITY_ESCALATION, critical, optedOut)));
      }
    }
    return decisions;
  }

  /**
   * Why the channel cannot be used, or {@code null}. Below {@code CRITICAL} an opt-out is reported
   * before a missing address; for {@code CRITICAL} only a missing address counts.
   */
  private static String unusable(
      Channel channel, boolean critical, Set<Channel> optedOut, AddressPresence addresses) {
    if (!critical && optedOut.contains(channel)) {
      return OPTED_OUT;
    }
    if (!addresses.hasAddressFor(channel)) {
      return NO_ADDRESS;
    }
    return null;
  }

  /** A CRITICAL channel the recipient opted out of is labelled as an override. */
  private static RoutingReason selectedReason(
      Channel channel, RoutingReason reason, boolean critical, Set<Channel> optedOut) {
    return critical && optedOut.contains(channel)
        ? RoutingReason.OPT_OUT_OVERRIDDEN_CRITICAL
        : reason;
  }
}
