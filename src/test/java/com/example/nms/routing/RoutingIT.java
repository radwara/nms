package com.example.nms.routing;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.TestEntities.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.delivery.Delivery;
import com.example.nms.delivery.DeliveryRepository;
import com.example.nms.delivery.RoutingReason;
import com.example.nms.intake.IntakeService;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import com.example.nms.intake.RoutingStep;
import com.example.nms.intake.SubmitCommand;
import com.example.nms.intake.SubmitResult;
import com.example.nms.intake.SubmittedNotification;
import com.example.nms.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * #9: routing through a real submission against the seeded directory and the default policy in
 * {@code application.yml} ({@code CRITICAL: [SMS]}, {@code sim-email}, {@code sim-sms}). The clock
 * is fixed at {@link com.example.nms.support.TestEntities#NOW}.
 */
@SpringBootTest
@Import(RoutingIT.ClockConfiguration.class)
class RoutingIT {

  private static final List<Channel> EMAIL = List.of(Channel.EMAIL);
  private static final List<Channel> SMS = List.of(Channel.SMS);
  private static final List<Channel> SMS_EMAIL = List.of(Channel.SMS, Channel.EMAIL);

  @Autowired private IntakeService intake;
  @Autowired private RoutingStep routing;
  @Autowired private NotificationRepository notifications;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void requestedChannelsAreTheCandidates() {
    UUID id = submitOne(Severity.INFO, "user-ok", SMS);

    assertThat(routes(id)).containsExactly("SMS REQUESTED");
    assertThat(history(id)).containsExactly("ACCEPTED", "ROUTED SMS REQUESTED", "QUEUED SMS");
  }

  @Test
  void withoutChannelsTheRecipientsEnabledChannelsAreUsed() {
    UUID id = submitOne(Severity.INFO, "user-ok", null);

    assertThat(routes(id)).containsExactly("EMAIL PREFERENCE", "SMS PREFERENCE");
  }

  @Test
  void optedOutChannelIsRemovedAndAudited() {
    UUID id = submitOne(Severity.INFO, "user-sms-optout", SMS_EMAIL);

    assertThat(routes(id)).containsExactly("EMAIL REQUESTED");
    assertThat(history(id))
        .containsExactly(
            "ACCEPTED", "ROUTE_REMOVED SMS OPTED_OUT", "ROUTED EMAIL REQUESTED", "QUEUED EMAIL");
  }

  @Test
  void channelWithoutAddressIsRemovedAndAudited() {
    UUID id = submitOne(Severity.INFO, "user-email-only", SMS_EMAIL);

    assertThat(routes(id)).containsExactly("EMAIL REQUESTED");
    assertThat(history(id)).contains("ROUTE_REMOVED SMS NO_ADDRESS");
  }

  @Test
  void criticalAddsSms() {
    UUID id = submitOne(Severity.CRITICAL, "user-ok", EMAIL);

    assertThat(routes(id)).containsExactly("EMAIL REQUESTED", "SMS SEVERITY_ESCALATION");
    assertThat(history(id))
        .containsExactly(
            "ACCEPTED",
            "ROUTED EMAIL REQUESTED",
            "ROUTED SMS SEVERITY_ESCALATION",
            "QUEUED EMAIL",
            "QUEUED SMS");
  }

  @Test
  void escalationIsWithheldWhenOptedOutOfSms() {
    UUID id = submitOne(Severity.CRITICAL, "user-sms-optout", EMAIL);

    assertThat(routes(id)).containsExactly("EMAIL REQUESTED");
    assertThat(history(id))
        .containsExactly(
            "ACCEPTED",
            "ROUTED EMAIL REQUESTED",
            "ROUTE_REMOVED SMS SEVERITY_ESCALATION withheld: OPTED_OUT",
            "QUEUED EMAIL");
  }

  @Test
  void escalationIsWithheldWithoutAPhone() {
    UUID id = submitOne(Severity.CRITICAL, "user-email-only", EMAIL);

    assertThat(routes(id)).containsExactly("EMAIL REQUESTED");
    assertThat(history(id)).contains("ROUTE_REMOVED SMS SEVERITY_ESCALATION withheld: NO_ADDRESS");
  }

  @Test
  void channelBothRequestedAndEscalatedIsRoutedOnceAsRequested() {
    UUID id = submitOne(Severity.CRITICAL, "user-ok", SMS);

    assertThat(routes(id)).containsExactly("SMS REQUESTED");
    assertThat(history(id)).containsExactly("ACCEPTED", "ROUTED SMS REQUESTED", "QUEUED SMS");
  }

  @Test
  void noChannelLeftMeansNoDeliveriesAndNotRouted() {
    UUID id = submitOne(Severity.INFO, "user-sms-optout", SMS);

    assertThat(deliveriesOf(id)).isEmpty();
    assertThat(history(id))
        .containsExactly("ACCEPTED", "ROUTE_REMOVED SMS OPTED_OUT", "NOT_ROUTED no usable channel");
  }

  @Test
  void deliveryIsPendingWithReasonPriorityProviderAndDueNow() {
    UUID id = submit(command(Severity.INFO, Priority.LOW, null, "user-ok", EMAIL)).getFirst();

    Delivery delivery = deliveriesOf(id).getFirst();
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(delivery.getRoutingReason()).isEqualTo(RoutingReason.REQUESTED);
    assertThat(delivery.getProvider()).isEqualTo("sim-email");
    assertThat(delivery.getPriority()).isEqualTo(Priority.LOW);
    assertThat(delivery.getAttemptCount()).isZero();
    assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW);
    assertThat(delivery.getCreatedAt()).isEqualTo(NOW);
    assertThat(
            jdbc.queryForObject(
                "select priority from delivery where id = ?", Integer.class, delivery.getId()))
        .isEqualTo((int) Priority.LOW.code());
  }

  @Test
  void eachChannelGetsItsConfiguredProvider() {
    UUID id = submitOne(Severity.INFO, "user-ok", null);

    assertThat(deliveriesOf(id))
        .extracting(Delivery::getChannel, Delivery::getProvider)
        .containsExactly(tuple(Channel.EMAIL, "sim-email"), tuple(Channel.SMS, "sim-sms"));
  }

  @Test
  void futureScheduleIsTheDueTime() {
    Instant scheduledAt = NOW.plus(Duration.ofHours(1));
    UUID id =
        submit(command(Severity.INFO, Priority.NORMAL, scheduledAt, "user-ok", EMAIL)).getFirst();

    assertThat(deliveriesOf(id).getFirst().getNextAttemptAt()).isEqualTo(scheduledAt);
  }

  @Test
  void pastScheduleIsDueNow() {
    Instant scheduledAt = NOW.minus(Duration.ofHours(1));
    UUID id =
        submit(command(Severity.INFO, Priority.NORMAL, scheduledAt, "user-ok", EMAIL)).getFirst();

    assertThat(deliveriesOf(id).getFirst().getNextAttemptAt()).isEqualTo(NOW);
  }

  @Test
  void auditOrderIsAcceptedThenDecisionsThenQueuedWithMatchingDeliveryIds() {
    UUID id = submitOne(Severity.CRITICAL, "user-email-only", null);

    assertThat(history(id))
        .containsExactly(
            "ACCEPTED",
            "ROUTED EMAIL PREFERENCE",
            "ROUTE_REMOVED SMS NO_ADDRESS",
            "ROUTE_REMOVED SMS SEVERITY_ESCALATION withheld: NO_ADDRESS",
            "QUEUED EMAIL");
    UUID deliveryId = deliveriesOf(id).getFirst().getId();
    assertThat(
            jdbc.queryForList(
                "select delivery_id from audit_event where notification_id = ?"
                    + " and type in ('ROUTED', 'QUEUED')",
                UUID.class,
                id))
        .containsOnly(deliveryId)
        .hasSize(2);
  }

  @Test
  void seededRecipientsRouteAsDocumented() {
    List<UUID> ids =
        submit(
            new SubmitCommand(
                "demo-app",
                "key-" + UUID.randomUUID(),
                "evt-1",
                "FRAUD_ALERT",
                Severity.CRITICAL,
                Priority.HIGH,
                Stream.of("user-ok", "user-email-only", "user-sms-optout")
                    .map(userId -> new SubmitCommand.Recipient(userId, null))
                    .toList(),
                "subject",
                "body",
                null,
                null));

    assertThat(routes(ids.get(0))).containsExactly("EMAIL PREFERENCE", "SMS PREFERENCE");
    assertThat(routes(ids.get(1))).containsExactly("EMAIL PREFERENCE");
    assertThat(routes(ids.get(2))).containsExactly("EMAIL PREFERENCE");
    assertThat(history(ids.get(2)))
        .containsExactly(
            "ACCEPTED",
            "ROUTED EMAIL PREFERENCE",
            "ROUTE_REMOVED SMS SEVERITY_ESCALATION withheld: OPTED_OUT",
            "QUEUED EMAIL");
  }

  @Test
  void replayDoesNotRouteAgain() {
    SubmitCommand command = command(Severity.CRITICAL, Priority.HIGH, null, "user-ok", null);
    UUID id = submit(command).getFirst();
    List<String> routed = history(id);

    assertThat(intake.submit(command)).isInstanceOf(SubmitResult.Replayed.class);

    assertThat(jdbc.queryForObject("select count(*) from delivery", Integer.class)).isEqualTo(2);
    List<String> expected = new ArrayList<>(routed);
    expected.add("DUPLICATE_SUBMISSION same Idempotency-Key; original returned");
    assertThat(history(id)).isEqualTo(expected);
  }

  @Test
  void routingOnlyRunsInsideTheSubmitTransaction() {
    Notification notification = storedNotification();

    assertThatThrownBy(() -> routing.route(List.of(notification), NOW))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(jdbc.queryForObject("select count(*) from delivery", Integer.class)).isZero();
  }

  /** A committed notification with no routing, as a direct call would see it. */
  private Notification storedNotification() {
    UUID id = submitOne(Severity.INFO, "user-ok", EMAIL);
    jdbc.update("delete from delivery");
    return notifications.findById(id).orElseThrow();
  }

  private UUID submitOne(Severity severity, String userId, List<Channel> channels) {
    return submit(command(severity, Priority.NORMAL, null, userId, channels)).getFirst();
  }

  private List<UUID> submit(SubmitCommand command) {
    SubmitResult result = intake.submit(command);
    assertThat(result).isInstanceOf(SubmitResult.Created.class);
    return ((SubmitResult.Created) result)
        .notifications().stream().map(SubmittedNotification::notificationId).toList();
  }

  private static SubmitCommand command(
      Severity severity,
      Priority priority,
      Instant scheduledAt,
      String userId,
      List<Channel> channels) {
    return new SubmitCommand(
        "demo-app",
        "key-" + UUID.randomUUID(),
        "evt-1",
        "FRAUD_ALERT",
        severity,
        priority,
        List.of(new SubmitCommand.Recipient(userId, channels)),
        "subject",
        "body",
        scheduledAt,
        null);
  }

  private List<Delivery> deliveriesOf(UUID notificationId) {
    return deliveries.findAll().stream()
        .filter(d -> d.getNotificationId().equals(notificationId))
        .sorted(Comparator.comparing(Delivery::getChannel))
        .toList();
  }

  /** "CHANNEL REASON" per delivery, in channel order. */
  private List<String> routes(UUID notificationId) {
    return deliveriesOf(notificationId).stream()
        .map(d -> d.getChannel() + " " + d.getRoutingReason())
        .toList();
  }

  /** "TYPE [CHANNEL] [DETAIL]" per audit event, ordered by occurred_at, id. */
  private List<String> history(UUID notificationId) {
    return jdbc.query(
        "select type, channel, detail from audit_event where notification_id = ?"
            + " order by occurred_at, id",
        (rs, row) ->
            Stream.of(rs.getString("type"), rs.getString("channel"), rs.getString("detail"))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" ")),
        notificationId);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(NOW);
    }
  }
}
