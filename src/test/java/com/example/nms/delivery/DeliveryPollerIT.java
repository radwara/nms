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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #10: with the worker enabled, the scheduled poller (default 500ms interval, batch 20) sends
 * without anyone calling it, and drains a backlog without waiting an interval per batch.
 */
@SpringBootTest(properties = "nms.worker.enabled=true")
class DeliveryPollerIT {

  private static final int BACKLOG = 1_000;

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

  @Test
  void backlogDrainsWithoutWaitingAPollIntervalPerBatch() throws InterruptedException {
    clearSubmissions(jdbc);
    Instant now = clock.instant();
    List<Notification> created = new ArrayList<>();
    for (int i = 0; i < BACKLOG; i++) {
      created.add(notification("user-ok", Priority.NORMAL, now, null));
    }
    List<Delivery> backlog = new ArrayList<>();
    for (Notification notification : notifications.saveAll(created)) {
      backlog.add(delivery(notification, Channel.EMAIL, now));
    }
    long started = System.nanoTime();
    deliveries.saveAll(backlog);

    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (sent() < BACKLOG && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    assertThat(sent()).isEqualTo(BACKLOG);
    // One batch per 500ms interval would take 25s or more.
    assertThat(took).isLessThan(Duration.ofSeconds(10));
  }

  private long sent() {
    return jdbc.queryForObject("select count(*) from delivery where status = 'SENT'", Long.class);
  }

  private DeliveryStatus status(Delivery delivery) {
    return deliveries.findById(delivery.getId()).orElseThrow().getStatus();
  }
}
