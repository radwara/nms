package com.example.nms.intake;

import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

/** #7: notifications insert as new, and requested channels round-trip in request order. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NotificationMappingIT {

  @Autowired private NotificationRepository notifications;

  @Autowired private EntityManager entityManager;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void newNotificationIsInsertedAndNoLongerNew() {
    Notification notification = notification();
    assertThat(notification.isNew()).isTrue();

    Notification saved = notifications.saveAndFlush(notification);

    assertThat(saved).isSameAs(notification);
    assertThat(saved.isNew()).isFalse();
  }

  @Test
  void loadedNotificationIsNotNew() {
    Notification saved = notifications.saveAndFlush(notification());
    entityManager.clear();

    assertThat(notifications.findById(saved.getId()))
        .hasValueSatisfying(n -> assertThat(n.isNew()).isFalse());
  }

  @Test
  void requestedChannelsRoundTripInRequestOrder() {
    Notification saved =
        notifications.saveAndFlush(notification(List.of(Channel.SMS, Channel.EMAIL)));
    entityManager.clear();

    assertThat(
            jdbc.queryForObject(
                "select requested_channels from notification where id = ?",
                String.class,
                saved.getId()))
        .isEqualTo("SMS,EMAIL");
    assertThat(notifications.findById(saved.getId()).orElseThrow().getRequestedChannels())
        .containsExactly(Channel.SMS, Channel.EMAIL);
  }

  @Test
  void absentRequestedChannelsAreStoredAsNull() {
    Notification saved = notifications.saveAndFlush(notification());
    entityManager.clear();

    assertThat(
            jdbc.queryForObject(
                "select requested_channels from notification where id = ?",
                String.class,
                saved.getId()))
        .isNull();
    assertThat(notifications.findById(saved.getId()).orElseThrow().getRequestedChannels()).isNull();
  }
}
