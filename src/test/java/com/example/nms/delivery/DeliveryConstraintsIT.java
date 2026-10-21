package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
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
    notificationId = UUID.randomUUID();
    notifications.saveAndFlush(
        new Notification(
            notificationId,
            UUID.randomUUID(),
            "billing",
            "evt-1",
            "PAYMENT_DUE",
            Severity.INFO,
            Priority.HIGH,
            "user-ok",
            null,
            "subject",
            "body",
            null,
            null,
            Instant.now()));
  }

  @Test
  void secondDeliveryForSameNotificationAndChannelIsRejected() {
    deliveries.saveAndFlush(delivery(Channel.EMAIL));

    assertThatThrownBy(() -> deliveries.saveAndFlush(delivery(Channel.EMAIL)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void oneDeliveryPerChannelIsAccepted() {
    deliveries.saveAndFlush(delivery(Channel.EMAIL));
    deliveries.saveAndFlush(delivery(Channel.SMS));

    assertThat(deliveries.count()).isEqualTo(2);
  }

  @Test
  void newDeliveryIsPendingWithNumericPriorityAndInitialVersion() {
    Delivery saved = deliveries.saveAndFlush(delivery(Channel.EMAIL));

    assertThat(saved.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(saved.getVersion()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select priority from delivery where id = ?", Short.class, saved.getId()))
        .isEqualTo(Priority.HIGH.code());
  }

  @Test
  void duplicateProviderMessageIdIsRejected() {
    Delivery email = deliveries.saveAndFlush(delivery(Channel.EMAIL));
    Delivery sms = deliveries.saveAndFlush(delivery(Channel.SMS));
    String sameProvider = "update delivery set provider = 'sim', provider_message_id = 'm-1'";
    jdbc.update(sameProvider + " where id = ?", email.getId());

    assertThatThrownBy(() -> jdbc.update(sameProvider + " where id = ?", sms.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private Delivery delivery(Channel channel) {
    Instant now = Instant.now();
    return new Delivery(
        UUID.randomUUID(),
        notificationId,
        channel,
        "sim-" + channel.name().toLowerCase(Locale.ROOT),
        Priority.HIGH,
        "REQUESTED",
        now,
        now);
  }
}
