package com.example.nms.routing;

import static com.example.nms.common.Channel.EMAIL;
import static com.example.nms.common.Channel.SMS;
import static com.example.nms.delivery.RoutingReason.PREFERENCE;
import static com.example.nms.delivery.RoutingReason.REQUESTED;
import static com.example.nms.delivery.RoutingReason.SEVERITY_ESCALATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.directory.AddressPresence;
import com.example.nms.routing.ChannelSelection.Removed;
import com.example.nms.routing.ChannelSelection.Selected;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** #9: channel decisions for one notification (FR-3.1, A-25, A-30 greenfield baseline). */
class ChannelSelectionTest {

  private static final AddressPresence BOTH = new AddressPresence("u", true, true);
  private static final AddressPresence EMAIL_ONLY = new AddressPresence("u", true, false);
  private static final AddressPresence NONE = new AddressPresence("u", false, false);
  private static final List<Channel> NO_ESCALATION = List.of();
  private static final List<Channel> SMS_ESCALATION = List.of(SMS);

  @Test
  void requestedChannelsAreTheCandidatesInRequestOrder() {
    assertThat(ChannelSelection.decide(List.of(SMS, EMAIL), NO_ESCALATION, Set.of(), BOTH))
        .containsExactly(new Selected(SMS, REQUESTED), new Selected(EMAIL, REQUESTED));
  }

  @Test
  void withoutRequestedChannelsTheEnabledChannelsAreUsed() {
    assertThat(ChannelSelection.decide(null, NO_ESCALATION, Set.of(), BOTH))
        .containsExactly(new Selected(EMAIL, PREFERENCE), new Selected(SMS, PREFERENCE));
  }

  @Test
  void preferencesSkipOptedOutChannels() {
    assertThat(ChannelSelection.decide(null, NO_ESCALATION, Set.of(SMS), BOTH))
        .containsExactly(new Selected(EMAIL, PREFERENCE));
  }

  @Test
  void optedOutRequestedChannelIsRemoved() {
    assertThat(ChannelSelection.decide(List.of(SMS, EMAIL), NO_ESCALATION, Set.of(SMS), BOTH))
        .containsExactly(new Removed(SMS, "OPTED_OUT"), new Selected(EMAIL, REQUESTED));
  }

  @Test
  void channelWithoutAddressIsRemoved() {
    assertThat(ChannelSelection.decide(List.of(SMS, EMAIL), NO_ESCALATION, Set.of(), EMAIL_ONLY))
        .containsExactly(new Removed(SMS, "NO_ADDRESS"), new Selected(EMAIL, REQUESTED));
    assertThat(ChannelSelection.decide(null, NO_ESCALATION, Set.of(), EMAIL_ONLY))
        .containsExactly(new Selected(EMAIL, PREFERENCE), new Removed(SMS, "NO_ADDRESS"));
  }

  @Test
  void optOutIsReportedBeforeAMissingAddress() {
    assertThat(ChannelSelection.decide(List.of(SMS), NO_ESCALATION, Set.of(SMS), NONE))
        .containsExactly(new Removed(SMS, "OPTED_OUT"));
  }

  @Test
  void escalationAddsItsChannelAfterTheCandidates() {
    assertThat(ChannelSelection.decide(List.of(EMAIL), SMS_ESCALATION, Set.of(), BOTH))
        .containsExactly(new Selected(EMAIL, REQUESTED), new Selected(SMS, SEVERITY_ESCALATION));
  }

  @Test
  void escalationIsWithheldWhenOptedOut() {
    assertThat(ChannelSelection.decide(List.of(EMAIL), SMS_ESCALATION, Set.of(SMS), BOTH))
        .containsExactly(
            new Selected(EMAIL, REQUESTED),
            new Removed(SMS, "SEVERITY_ESCALATION withheld: OPTED_OUT"));
  }

  @Test
  void escalationIsWithheldWithoutAnAddress() {
    assertThat(ChannelSelection.decide(List.of(EMAIL), SMS_ESCALATION, Set.of(), EMAIL_ONLY))
        .containsExactly(
            new Selected(EMAIL, REQUESTED),
            new Removed(SMS, "SEVERITY_ESCALATION withheld: NO_ADDRESS"));
  }

  @Test
  void removedCandidateIsAlsoReportedAsAWithheldEscalation() {
    assertThat(ChannelSelection.decide(List.of(SMS, EMAIL), SMS_ESCALATION, Set.of(SMS), BOTH))
        .containsExactly(
            new Removed(SMS, "OPTED_OUT"),
            new Selected(EMAIL, REQUESTED),
            new Removed(SMS, "SEVERITY_ESCALATION withheld: OPTED_OUT"));
  }

  @Test
  void channelBothRequestedAndEscalatedKeepsRequested() {
    assertThat(ChannelSelection.decide(List.of(SMS), SMS_ESCALATION, Set.of(), BOTH))
        .containsExactly(new Selected(SMS, REQUESTED));
  }

  @Test
  void channelFromPreferencesAndEscalatedKeepsPreference() {
    assertThat(ChannelSelection.decide(null, SMS_ESCALATION, Set.of(), BOTH))
        .containsExactly(new Selected(EMAIL, PREFERENCE), new Selected(SMS, PREFERENCE));
  }

  @Test
  void escalationListedTwiceIsDecidedOnce() {
    assertThat(ChannelSelection.decide(List.of(EMAIL), List.of(SMS, SMS), Set.of(SMS), BOTH))
        .containsExactly(
            new Selected(EMAIL, REQUESTED),
            new Removed(SMS, "SEVERITY_ESCALATION withheld: OPTED_OUT"));
  }

  @Test
  void nothingSelectedWhenNoChannelIsUsable() {
    assertThat(ChannelSelection.decide(null, SMS_ESCALATION, Set.of(), NONE))
        .containsExactly(
            new Removed(EMAIL, "NO_ADDRESS"),
            new Removed(SMS, "NO_ADDRESS"),
            new Removed(SMS, "SEVERITY_ESCALATION withheld: NO_ADDRESS"))
        .noneMatch(Selected.class::isInstance);
  }
}
