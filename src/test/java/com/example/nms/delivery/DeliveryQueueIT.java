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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #10, ADR-0004: the queue's poll queries and conditional writes, called outside a transaction as
 * the worker calls them. State is read back over a separate connection, so it shows what is
 * committed.
 */
@SpringBootTest
class DeliveryQueueIT {

  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void claimIsCommittedWhenItReturns() {
    Delivery due = queue(Priority.NORMAL, NOW);

    int claimed = deliveries.claim(due.getId(), DeliveryStatus.PENDING, 0, NOW.plusSeconds(1));

    assertThat(claimed).isOne();
    Map<String, Object> row =
        jdbc.queryForMap(
            "select status, version, attempt_count, claimed_at, updated_at from delivery where id = ?",
            due.getId());
    assertThat(row)
        .containsEntry("status", "SENDING")
        .containsEntry("version", 1L)
        .containsEntry("attempt_count", 1);
    assertThat(instant(row.get("claimed_at"))).isEqualTo(NOW.plusSeconds(1));
    assertThat(instant(row.get("updated_at"))).isEqualTo(NOW.plusSeconds(1));
  }

  @Test
  void claimWithAStaleVersionOrStatusUpdatesNothing() {
    Delivery due = queue(Priority.NORMAL, NOW);
    deliveries.claim(due.getId(), DeliveryStatus.PENDING, 0, NOW);

    assertThat(deliveries.claim(due.getId(), DeliveryStatus.PENDING, 0, NOW)).isZero();
    assertThat(deliveries.claim(due.getId(), DeliveryStatus.SENDING, 0, NOW)).isZero();
    assertThat(version(due.getId())).isEqualTo(1L);
  }

  @Test
  void twoConcurrentClaimersOneWins() throws Exception {
    for (int round = 0; round < 20; round++) {
      Delivery due = queue(Priority.NORMAL, NOW);
      CyclicBarrier start = new CyclicBarrier(2);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        List<Future<Integer>> claims = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
          claims.add(
              pool.submit(
                  () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return deliveries.claim(due.getId(), DeliveryStatus.PENDING, 0, NOW);
                  }));
        }
        int won = 0;
        for (Future<Integer> claim : claims) {
          won += claim.get(10, TimeUnit.SECONDS);
        }
        assertThat(won).as("round %d", round).isOne();
        assertThat(version(due.getId())).isEqualTo(1L);
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void outcomeWriteNeedsTheClaimVersion() {
    Delivery due = queue(Priority.NORMAL, NOW);
    deliveries.claim(due.getId(), DeliveryStatus.PENDING, 0, NOW);
    // Reclaimed by another worker: version 2.
    deliveries.claim(due.getId(), DeliveryStatus.SENDING, 1, NOW.plusSeconds(31));

    assertThat(deliveries.markSent(due.getId(), 1, "em-stale", NOW)).isZero();
    assertThat(deliveries.markSent(due.getId(), 2, "em-1", NOW.plusSeconds(32))).isOne();
    assertThat(jdbc.queryForMap("select status, version, provider_message_id from delivery"))
        .containsEntry("status", "SENT")
        .containsEntry("version", 3L)
        .containsEntry("provider_message_id", "em-1");
  }

  @Test
  void pollReturnsOneStatusDueRowsByPriorityThenDueTime() {
    Delivery lowEarly = queue(Priority.LOW, NOW.minusSeconds(30));
    Delivery normalLate = queue(Priority.NORMAL, NOW.minusSeconds(1));
    Delivery normalEarly = queue(Priority.NORMAL, NOW.minusSeconds(5));
    Delivery high = queue(Priority.HIGH, NOW);
    queue(Priority.HIGH, NOW.plusNanos(1_000)); // not due yet
    Delivery claimed = queue(Priority.HIGH, NOW.minusSeconds(60));
    deliveries.claim(claimed.getId(), DeliveryStatus.PENDING, 0, NOW);

    assertThat(deliveries.findDue(DeliveryStatus.PENDING, NOW, Limit.of(10)))
        .extracting(Delivery::getId)
        .containsExactly(high.getId(), normalEarly.getId(), normalLate.getId(), lowEarly.getId());
    assertThat(deliveries.findDue(DeliveryStatus.PENDING, NOW, Limit.of(2)))
        .extracting(Delivery::getId)
        .containsExactly(high.getId(), normalEarly.getId());
    assertThat(deliveries.findDue(DeliveryStatus.RETRY_SCHEDULED, NOW, Limit.of(10))).isEmpty();
  }

  @Test
  void leaseExpiredPollReturnsOnlyClaimsOlderThanTheLease() {
    Delivery stale = queue(Priority.NORMAL, NOW);
    Delivery fresh = queue(Priority.NORMAL, NOW);
    deliveries.claim(stale.getId(), DeliveryStatus.PENDING, 0, NOW);
    deliveries.claim(fresh.getId(), DeliveryStatus.PENDING, 0, NOW.plusSeconds(1));
    Instant now = NOW.plusSeconds(31);

    assertThat(deliveries.findLeaseExpired(now, now.minus(Duration.ofSeconds(30)), Limit.of(10)))
        .extracting(Delivery::getId)
        .containsExactly(stale.getId());
  }

  private Delivery queue(Priority priority, Instant dueAt) {
    Notification notification =
        notifications.save(notification("user-ok", priority, NOW.minusSeconds(120), null));
    return deliveries.save(delivery(notification, Channel.EMAIL, dueAt));
  }

  private long version(UUID id) {
    return jdbc.queryForObject("select version from delivery where id = ?", Long.class, id);
  }

  private static Instant instant(Object timestamp) {
    return switch (timestamp) {
      case OffsetDateTime odt -> odt.toInstant();
      case Timestamp ts -> ts.toInstant();
      default -> throw new IllegalArgumentException(timestamp.getClass().getName());
    };
  }
}
