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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #47, ADR-0008: the poller's {@code PENDING} query with the Kafka grace window leaves an EMAIL row
 * to the fast path while it was created within the grace and never moved; everything else is
 * returned when due, in the usual order.
 */
@SpringBootTest
class DeliveryGraceQueryIT {

  private static final Duration GRACE = Duration.ofSeconds(30);

  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void emailCreatedWithinTheGraceIsLeftOutWhileSmsAndOlderEmailAreReturnedEarliestFirst() {
    Delivery emailOld = queue(Channel.EMAIL, NOW.minus(GRACE).minusSeconds(1), NOW.minusSeconds(9));
    Delivery emailAtCutoff = queue(Channel.EMAIL, NOW.minus(GRACE), NOW.minusSeconds(8));
    queue(Channel.EMAIL, NOW.minus(GRACE).plusSeconds(1), NOW.minusSeconds(10)); // in grace
    queue(Channel.EMAIL, NOW, NOW); // in grace
    Delivery smsNow = queue(Channel.SMS, NOW, NOW);
    Delivery smsEarlier = queue(Channel.SMS, NOW.minusSeconds(5), NOW.minusSeconds(5));
    queue(Channel.SMS, NOW, NOW.plusSeconds(1)); // not due yet

    assertThat(poll(NOW, 10))
        .containsExactly(
            emailOld.getId(), emailAtCutoff.getId(), smsEarlier.getId(), smsNow.getId());
  }

  @Test
  void inGraceRowsDoNotUseUpTheLimit() {
    for (int i = 0; i < 5; i++) {
      queue(Channel.EMAIL, NOW.minusSeconds(10), NOW.minusSeconds(10)); // in grace, ahead of SMS
    }
    Delivery sms = queue(Channel.SMS, NOW, NOW);

    assertThat(poll(NOW, 2)).containsExactly(sms.getId());
  }

  /** Scheduled further ahead than the grace: the poller takes it as soon as it is due. */
  @Test
  void emailScheduledBeyondTheGraceIsReturnedOnceDue() {
    Instant due = NOW.plus(GRACE).plusSeconds(30);
    Delivery scheduled = queue(Channel.EMAIL, NOW, due);

    assertThat(poll(due.minusSeconds(1), 10)).isEmpty();
    assertThat(poll(due.plusSeconds(1), 10)).containsExactly(scheduled.getId());
  }

  /** Scheduled within the grace: left to the fast path until creation + grace, not due + grace. */
  @Test
  void emailScheduledWithinTheGraceIsReturnedAtCreationPlusGrace() {
    Delivery scheduled = queue(Channel.EMAIL, NOW, NOW.plusSeconds(1));

    assertThat(poll(NOW.plusSeconds(2), 10)).isEmpty();
    assertThat(poll(NOW.plus(GRACE), 10)).containsExactly(scheduled.getId());
  }

  /** A rate-limit deferral bumps the version: the poller takes the row at its window end. */
  @Test
  void rateDeferredEmailIsReturnedAtItsWindowEnd() {
    Delivery deferred = queue(Channel.EMAIL, NOW, NOW);
    Instant windowEnd = NOW.plusSeconds(10);
    assertThat(deliveries.postponeDue("billing", Channel.EMAIL, NOW, NOW, windowEnd)).isOne();

    assertThat(poll(windowEnd.minusNanos(1_000), 10)).isEmpty();
    assertThat(poll(windowEnd, 10)).containsExactly(deferred.getId());
  }

  /** An error postpone bumps the version too: the poller takes the row when it is due again. */
  @Test
  void postponedEmailIsReturnedWhenDueAgain() {
    Delivery postponed = queue(Channel.EMAIL, NOW, NOW);
    Instant next = NOW.plusSeconds(1);
    assertThat(deliveries.postpone(postponed.getId(), DeliveryStatus.PENDING, 0, next, NOW))
        .isOne();

    assertThat(poll(next, 10)).containsExactly(postponed.getId());
  }

  private List<UUID> poll(Instant now, int limit) {
    return deliveries
        .findDueOutsideGrace(
            DeliveryStatus.PENDING,
            Priority.NORMAL,
            now,
            Channel.EMAIL,
            now.minus(GRACE),
            Limit.of(limit))
        .stream()
        .map(Delivery::getId)
        .toList();
  }

  private Delivery queue(Channel channel, Instant createdAt, Instant dueAt) {
    Notification notification =
        notifications.save(notification("user-ok", Priority.NORMAL, createdAt, null));
    return deliveries.save(delivery(notification, channel, dueAt));
  }
}
