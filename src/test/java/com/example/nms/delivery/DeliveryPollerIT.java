package com.example.nms.delivery;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** #10: with the worker enabled, the scheduled poller sends without anyone calling it. */
@SpringBootTest(properties = {"nms.worker.enabled=true", "nms.worker.poll-interval=50ms"})
class DeliveryPollerIT {

  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private Clock clock;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void scheduledPollerSendsDueDeliveries() throws InterruptedException {
    clearSubmissions(jdbc);
    Instant now = clock.instant();
    Notification notification =
        notifications.save(notification("user-ok", Priority.NORMAL, now, null));
    Delivery queued = deliveries.save(delivery(notification, Channel.EMAIL, now));

    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (status(queued) != DeliveryStatus.SENT && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }

    assertThat(status(queued)).isEqualTo(DeliveryStatus.SENT);
  }

  private DeliveryStatus status(Delivery delivery) {
    return deliveries.findById(delivery.getId()).orElseThrow().getStatus();
  }
}
