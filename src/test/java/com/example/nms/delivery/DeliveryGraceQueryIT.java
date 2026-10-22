package com.example.nms.delivery;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.TestEntities.NOW;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #47, ADR-0008: the poller's {@code PENDING} query with the Kafka grace window leaves EMAIL rows
 * due within the grace to the fast path and returns everything else in the usual order.
 */
@SpringBootTest
class DeliveryGraceQueryIT {

  private static final Duration GRACE = Duration.ofSeconds(30);
  private static final Instant CUTOFF = NOW.minus(GRACE);

  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void inGraceEmailIsLeftOutWhileSmsAndPastGraceEmailAreReturnedEarliestFirst() {
    Delivery emailPastGrace = queue(Channel.EMAIL, CUTOFF.minusSeconds(1));
    Delivery emailAtCutoff = queue(Channel.EMAIL, CUTOFF);
    queue(Channel.EMAIL, CUTOFF.plusSeconds(1)); // in grace
    queue(Channel.EMAIL, NOW); // in grace
    Delivery smsNow = queue(Channel.SMS, NOW);
    Delivery smsEarlier = queue(Channel.SMS, NOW.minusSeconds(5));
    queue(Channel.SMS, NOW.plusSeconds(1)); // not due yet

    assertThat(
            deliveries.findDueOutsideGrace(
                DeliveryStatus.PENDING, Priority.NORMAL, NOW, Channel.EMAIL, CUTOFF, Limit.of(10)))
        .extracting(Delivery::getId)
        .containsExactly(
            emailPastGrace.getId(), emailAtCutoff.getId(), smsEarlier.getId(), smsNow.getId());
  }

  @Test
  void inGraceRowsDoNotUseUpTheLimit() {
    for (int i = 0; i < 5; i++) {
      queue(Channel.EMAIL, NOW.minusSeconds(10)); // in grace, ahead of the SMS row
    }
    Delivery sms = queue(Channel.SMS, NOW);

    assertThat(
            deliveries.findDueOutsideGrace(
                DeliveryStatus.PENDING, Priority.NORMAL, NOW, Channel.EMAIL, CUTOFF, Limit.of(2)))
        .extracting(Delivery::getId)
        .containsExactly(sms.getId());
  }

  private Delivery queue(Channel channel, Instant dueAt) {
    Notification notification =
        notifications.save(notification("user-ok", Priority.NORMAL, NOW.minusSeconds(120), null));
    return deliveries.save(delivery(notification, channel, dueAt));
  }
}
