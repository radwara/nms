package com.example.nms.routing;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.delivery.Delivery;
import com.example.nms.delivery.DeliveryRepository;
import com.example.nms.delivery.RoutingReason;
import com.example.nms.intake.IntakeService;
import com.example.nms.intake.SubmitCommand;
import com.example.nms.intake.SubmitResult;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #9 (A-26): the routing policy comes from configuration. These properties replace the default
 * {@code CRITICAL: [SMS]} and the SMS provider from {@code application.yml}.
 */
@SpringBootTest(
    properties = {
      "nms.routing.severity-escalation.CRITICAL=EMAIL",
      "nms.routing.severity-escalation.WARNING=SMS",
      "nms.routing.providers.SMS=alt-sms"
    })
class RoutingPolicyIT {

  @Autowired private IntakeService intake;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void criticalEscalatesToTheConfiguredChannelInsteadOfSms() {
    UUID id = submit(Severity.CRITICAL, List.of(Channel.SMS));

    assertThat(deliveriesOf(id))
        .extracting(Delivery::getChannel, Delivery::getRoutingReason, Delivery::getProvider)
        .containsExactly(
            tuple(Channel.EMAIL, RoutingReason.SEVERITY_ESCALATION, "sim-email"),
            tuple(Channel.SMS, RoutingReason.REQUESTED, "alt-sms"));
  }

  @Test
  void criticalNoLongerAddsSms() {
    UUID id = submit(Severity.CRITICAL, List.of(Channel.EMAIL));

    assertThat(deliveriesOf(id))
        .extracting(Delivery::getChannel, Delivery::getRoutingReason)
        .containsExactly(tuple(Channel.EMAIL, RoutingReason.REQUESTED));
  }

  @Test
  void configuredSeverityEscalatesToTheConfiguredProvider() {
    UUID id = submit(Severity.WARNING, List.of(Channel.EMAIL));

    assertThat(deliveriesOf(id))
        .extracting(Delivery::getChannel, Delivery::getRoutingReason, Delivery::getProvider)
        .containsExactly(
            tuple(Channel.EMAIL, RoutingReason.REQUESTED, "sim-email"),
            tuple(Channel.SMS, RoutingReason.SEVERITY_ESCALATION, "alt-sms"));
  }

  private UUID submit(Severity severity, List<Channel> channels) {
    SubmitResult result =
        intake.submit(
            new SubmitCommand(
                "demo-app",
                "key-" + UUID.randomUUID(),
                "evt-1",
                "FRAUD_ALERT",
                severity,
                Priority.NORMAL,
                List.of(new SubmitCommand.Recipient("user-ok", channels)),
                "subject",
                "body",
                null,
                null));
    assertThat(result).isInstanceOf(SubmitResult.Created.class);
    return ((SubmitResult.Created) result).notifications().getFirst().notificationId();
  }

  private List<Delivery> deliveriesOf(UUID notificationId) {
    return deliveries.findAll().stream()
        .filter(d -> d.getNotificationId().equals(notificationId))
        .sorted(Comparator.comparing(Delivery::getChannel))
        .toList();
  }
}
