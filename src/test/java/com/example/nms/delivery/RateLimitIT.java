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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #46: the worker with one EMAIL send per source system per minute. Time is a {@link MutableClock};
 * each test starts 10s into a fresh window, so the window ends 50s later. The poller is off; each
 * test calls {@link DeliveryWorker#runOnce()}.
 */
@SpringBootTest(properties = "nms.rate-limit.per-source.EMAIL=1")
@Import(RateLimitIT.TestBeans.class)
@ExtendWith(OutputCaptureExtension.class)
class RateLimitIT {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  @Autowired private DeliveryWorker worker;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private MutableClock clock;
  @Autowired private MeterRegistry registry;
  @Autowired private JdbcTemplate jdbc;

  private Instant windowEnd;

  @BeforeEach
  void freshWindow() {
    clearSubmissions(jdbc);
    long intoWindow = Math.floorMod(clock.instant().getEpochSecond(), WINDOW.toSeconds());
    clock.advance(WINDOW.minusSeconds(intoWindow).plusSeconds(10));
    windowEnd = clock.instant().plusSeconds(50);
  }

  @Test
  void overTheLimitStaysQueuedUntilTheWindowEndsWhileAnotherSourceSends(CapturedOutput output) {
    Instant now = clock.instant();
    Delivery first = queue("billing", "user-ok", now.minusSeconds(2), null);
    Delivery second = queue("billing", "user-ok", now.minusSeconds(1), null);
    Delivery other = queue("shipping", "user-ok", now, null);
    double deferredBefore = deferred();

    assertThat(worker.runOnce()).isEqualTo(2);

    assertThat(reload(first).getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(reload(other).getStatus()).as("another source").isEqualTo(DeliveryStatus.SENT);
    Delivery deferred = reload(second);
    assertThat(deferred.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(deferred.getAttemptCount()).isZero();
    assertThat(deferred.getNextAttemptAt()).isEqualTo(windowEnd);
    assertThat(history(second)).as("not a state change").isEmpty();
    assertThat(deferred()).isEqualTo(deferredBefore + 1);
    assertThat(output.getOut())
        .as("only a DEBUG line, which is off")
        .contains("Delivery " + first.getId() + " attempt 1")
        .doesNotContain(second.getId().toString());

    clock.advance(Duration.ofSeconds(49));
    worker.runOnce();
    assertThat(reload(second).getStatus()).as("not due yet").isEqualTo(DeliveryStatus.PENDING);

    clock.advance(Duration.ofSeconds(1));
    worker.runOnce();
    Delivery sent = reload(second);
    assertThat(sent.getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(sent.getAttemptCount()).isEqualTo(1);
  }

  @Test
  void retriesCountTowardTheLimit() {
    Delivery transientFailure = queue("billing", "user-transient", clock.instant(), null);

    worker.runOnce();
    assertThat(reload(transientFailure).getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    clock.advance(Duration.ofSeconds(1));
    worker.runOnce();

    Delivery deferred = reload(transientFailure);
    assertThat(deferred.getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    assertThat(deferred.getAttemptCount()).isEqualTo(1);
    assertThat(deferred.getNextAttemptAt()).isEqualTo(windowEnd);
  }

  @Test
  void aRowDeferredPastExpiresAtExpiresThroughTheExistingPath() {
    Instant now = clock.instant();
    queue("billing", "user-ok", now.minusSeconds(1), null);
    Delivery expiring = queue("billing", "user-ok", now, now.plusSeconds(30));

    worker.runOnce();
    assertThat(reload(expiring).getNextAttemptAt()).isEqualTo(windowEnd);
    clock.advance(Duration.ofSeconds(50));
    worker.runOnce();

    Delivery expired = reload(expiring);
    assertThat(expired.getStatus()).isEqualTo(DeliveryStatus.EXPIRED);
    assertThat(expired.getAttemptCount()).isZero();
    assertThat(history(expiring)).containsExactly("EXPIRED before send");
  }

  @Test
  void criticalHasNoBypass() {
    Instant now = clock.instant();
    queue("billing", "user-ok", now.minusSeconds(1), null);
    Delivery critical =
        deliveries.save(
            delivery(
                notifications.save(notification("billing", "user-ok", Severity.CRITICAL, null)),
                Channel.EMAIL,
                now));

    worker.runOnce();

    assertThat(reload(critical).getStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(reload(critical).getNextAttemptAt()).isEqualTo(windowEnd);
  }

  private Delivery queue(String sourceSystem, String recipient, Instant dueAt, Instant expiresAt) {
    Notification notification =
        notifications.save(notification(sourceSystem, recipient, Severity.INFO, expiresAt));
    return deliveries.save(delivery(notification, Channel.EMAIL, dueAt));
  }

  private Notification notification(
      String sourceSystem, String recipient, Severity severity, Instant expiresAt) {
    return new Notification(
        UUID.randomUUID(),
        UUID.randomUUID(),
        sourceSystem,
        "evt-1",
        "PAYMENT_DUE",
        severity,
        Priority.NORMAL,
        recipient,
        null,
        "subject",
        "body",
        null,
        expiresAt,
        clock.instant());
  }

  private double deferred() {
    Counter counter =
        registry
            .find("nms.delivery.deferred")
            .tags("channel", "EMAIL", "reason", "rate_limited")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private Delivery reload(Delivery delivery) {
    return deliveries.findById(delivery.getId()).orElseThrow();
  }

  private List<String> history(Delivery delivery) {
    return jdbc.query(
        "select type, detail from audit_event where delivery_id = ? order by occurred_at, id",
        (rs, i) ->
            rs.getString("type")
                + (rs.getString("detail") == null ? "" : " " + rs.getString("detail")),
        delivery.getId());
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
