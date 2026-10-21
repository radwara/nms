package com.example.nms.delivery;

import static com.example.nms.support.ConstraintAssertions.assertViolates;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.intake.NotificationRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

/** #7: delivery uniqueness is enforced by the database. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeliveryConstraintsIT {

  @Autowired private NotificationRepository notifications;

  @Autowired private DeliveryRepository deliveries;

  @Autowired private JdbcTemplate jdbc;

  private UUID notificationId;

  @BeforeEach
  void seedNotification() {
    notificationId = notifications.saveAndFlush(notification()).getId();
  }

  @Test
  void secondDeliveryForSameNotificationAndChannelIsRejected() {
    deliveries.saveAndFlush(delivery(notificationId, Channel.EMAIL));

    assertViolates(
        "uk_delivery_notification_channel",
        () -> deliveries.saveAndFlush(delivery(notificationId, Channel.EMAIL)));
  }

  @Test
  void oneDeliveryPerChannelIsAccepted() {
    deliveries.saveAndFlush(delivery(notificationId, Channel.EMAIL));
    deliveries.saveAndFlush(delivery(notificationId, Channel.SMS));

    assertThat(deliveries.count()).isEqualTo(2);
  }

  @Test
  void newDeliveryIsPendingWithNumericPriorityAndInitialVersion() {
    Delivery saved = deliveries.saveAndFlush(delivery(notificationId, Channel.EMAIL));

    assertThat(saved.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(saved.getVersion()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select priority from delivery where id = ?", Short.class, saved.getId()))
        .isEqualTo(Priority.HIGH.code());
    assertThat(
            jdbc.queryForObject(
                "select routing_reason from delivery where id = ?", String.class, saved.getId()))
        .isEqualTo("REQUESTED");
  }

  @Test
  void duplicateProviderMessageIdIsRejected() {
    Delivery email = deliveries.saveAndFlush(delivery(notificationId, Channel.EMAIL));
    Delivery sms = deliveries.saveAndFlush(delivery(notificationId, Channel.SMS));
    String sameProvider = "update delivery set provider = 'sim', provider_message_id = 'm-1'";
    jdbc.update(sameProvider + " where id = ?", email.getId());

    assertViolates(
        "uk_delivery_provider_message",
        () -> jdbc.update(sameProvider + " where id = ?", sms.getId()));
  }
}
