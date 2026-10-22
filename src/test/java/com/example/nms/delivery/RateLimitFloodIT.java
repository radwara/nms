package com.example.nms.delivery;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.TestEntities.NOW;
import static com.example.nms.support.TestEntities.delivery;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import com.example.nms.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #46 review: one source floods EMAIL (50 due rows, limit 5 per minute, batch 20) while another
 * source sends one row per window. The first denial defers the whole due backlog of the throttled
 * key with one set-based UPDATE, so the other source goes out on the next poll, every window.
 */
@SpringBootTest(
    properties = {
      "nms.rate-limit.per-source.EMAIL=5",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector="
          + "com.example.nms.delivery.DeliveryUpdateCounter"
    })
@Import(RateLimitFloodIT.TestBeans.class)
class RateLimitFloodIT {

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final int FLOOD = 50;
  private static final int LIMIT = 5;

  @Autowired private DeliveryWorker worker;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private MutableClock clock;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void windowStart() {
    clearSubmissions(jdbc);
    long intoWindow = Math.floorMod(clock.instant().getEpochSecond(), WINDOW.toSeconds());
    clock.advance(WINDOW.minusSeconds(intoWindow));
  }

  @Test
  void anotherSourceIsSentWithinTwoPollsOfEveryWindowWithOneSetBasedPostpone() {
    Instant start = clock.instant();
    List<UUID> flood = new ArrayList<>();
    for (int i = 0; i < FLOOD; i++) {
      // Created and due in order, before the window starts.
      Instant at = start.minusSeconds(FLOOD - i);
      flood.add(queue("flood", at, at).getId());
    }

    for (int window = 0; window < FLOOD / LIMIT; window++) {
      Instant windowEnd = clock.instant().plus(WINDOW);
      Delivery other = queue("other", clock.instant(), clock.instant());
      DeliveryUpdateCounter.reset();

      int polls = 0;
      while (status(other) != DeliveryStatus.SENT && polls < 10) {
        worker.runOnce();
        polls++;
      }
      worker.runOnce();

      boolean backlogLeft = window < FLOOD / LIMIT - 1;
      assertThat(polls)
          .as("polls until the other source is sent, window %d", window)
          .isLessThanOrEqualTo(2);
      assertThat(DeliveryUpdateCounter.SET_BASED_POSTPONES.get())
          .as("set-based postpones, window %d", window)
          .isEqualTo(backlogLeft ? 1 : 0);
      // Claim + SENT per sent row (LIMIT flood rows and the other one), plus the postpone.
      assertThat(DeliveryUpdateCounter.UPDATES.get())
          .as("delivery updates, window %d", window)
          .isEqualTo(2 * (LIMIT + 1) + (backlogLeft ? 1 : 0));
      assertThat(sentCount("flood")).isEqualTo((window + 1) * LIMIT);
      clock.advance(Duration.between(clock.instant(), windowEnd));
    }

    assertThat(sentOrder("flood")).as("FIFO within the source").containsExactlyElementsOf(flood);
  }

  private Delivery queue(String sourceSystem, Instant createdAt, Instant dueAt) {
    Notification notification =
        notifications.save(
            new Notification(
                UUID.randomUUID(),
                UUID.randomUUID(),
                sourceSystem,
                "evt-1",
                "PAYMENT_DUE",
                Severity.INFO,
                Priority.NORMAL,
                "user-ok",
                null,
                "subject",
                "body",
                null,
                null,
                createdAt));
    return deliveries.save(delivery(notification, Channel.EMAIL, dueAt));
  }

  private DeliveryStatus status(Delivery delivery) {
    return deliveries.findById(delivery.getId()).orElseThrow().getStatus();
  }

  private long sentCount(String sourceSystem) {
    return jdbc.queryForObject(
        "select count(*) from delivery d join notification n on n.id = d.notification_id"
            + " where n.source_system = ? and d.status = 'SENT'",
        Long.class,
        sourceSystem);
  }

  private List<UUID> sentOrder(String sourceSystem) {
    return jdbc.queryForList(
        "select a.delivery_id from audit_event a join notification n on n.id = a.notification_id"
            + " where a.type = 'SENT' and n.source_system = ? order by a.id",
        UUID.class,
        sourceSystem);
  }

  @TestConfiguration
  static class TestBeans {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(NOW);
    }
  }
}
