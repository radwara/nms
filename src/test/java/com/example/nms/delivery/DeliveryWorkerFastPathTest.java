package com.example.nms.delivery;

import static com.example.nms.support.TestEntities.NOW;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.directory.Recipient;
import com.example.nms.directory.RecipientRepository;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import com.example.nms.provider.ChannelProvider;
import com.example.nms.provider.ProviderRegistry;
import com.example.nms.provider.SendResult.Accepted;
import com.example.nms.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #47, ADR-0008: the poller's grace cutoff for EMAIL first dispatch, and {@link
 * DeliveryWorker#process} (the Kafka consumer's entry point) on the existing per-row path.
 */
class DeliveryWorkerFastPathTest {

  private static final Duration GRACE = Duration.ofSeconds(30);
  private static final Duration LEASE = Duration.ofSeconds(45);

  private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
  private final NotificationRepository notifications = mock(NotificationRepository.class);
  private final RecipientRepository recipients = mock(RecipientRepository.class);
  private final ProviderRegistry providers = mock(ProviderRegistry.class);
  private final DeliveryOutcomes outcomes = mock(DeliveryOutcomes.class);
  private final ChannelProvider provider = mock(ChannelProvider.class);

  private final Notification notification = notification("user-ok", Priority.NORMAL, NOW, null);

  @BeforeEach
  void directory() {
    when(notifications.findAllById(any())).thenReturn(List.of(notification));
    Recipient recipient = mock(Recipient.class);
    when(recipient.getId()).thenReturn("user-ok");
    when(recipient.getEmail()).thenReturn("ok.delivered@sim.test");
    when(recipients.findAllById(any())).thenReturn(List.of(recipient));
    when(providers.find("sim-email")).thenReturn(Optional.of(provider));
    when(provider.send(any())).thenReturn(new Accepted("em-1"));
    when(outcomes.sent(any(), any(), any())).thenReturn(true);
  }

  @Test
  void withAGraceOnlyPendingQueriesLeaveInGraceRowsOfTheChannel() {
    worker(Optional.of(new FirstDispatchGrace(Channel.EMAIL, GRACE))).runOnce();

    for (Priority priority : Priority.values()) {
      verify(deliveries)
          .findDueOutsideGrace(
              eq(DeliveryStatus.PENDING),
              eq(priority),
              eq(NOW),
              eq(Channel.EMAIL),
              eq(NOW.minus(GRACE)),
              any());
      verify(deliveries).findDue(eq(DeliveryStatus.RETRY_SCHEDULED), eq(priority), eq(NOW), any());
      verify(deliveries, never()).findDue(eq(DeliveryStatus.PENDING), eq(priority), any(), any());
    }
    verify(deliveries).findLeaseExpired(eq(NOW), eq(NOW.minus(LEASE)), any());
  }

  @Test
  void withoutAGraceThePollRunsTheSameQueriesAsBefore() {
    worker(Optional.empty()).runOnce();

    for (Priority priority : Priority.values()) {
      verify(deliveries).findDue(eq(DeliveryStatus.PENDING), eq(priority), eq(NOW), any());
      verify(deliveries).findDue(eq(DeliveryStatus.RETRY_SCHEDULED), eq(priority), eq(NOW), any());
    }
    verify(deliveries, never()).findDueOutsideGrace(any(), any(), any(), any(), any(), any());
    verify(deliveries).findLeaseExpired(eq(NOW), eq(NOW.minus(LEASE)), any());
  }

  @Test
  void graceCutoffIsNowMinusTheGrace() {
    Instant now = Instant.parse("2026-01-01T00:01:00Z");

    assertThat(new FirstDispatchGrace(Channel.EMAIL, GRACE).cutoff(now))
        .isEqualTo(Instant.parse("2026-01-01T00:00:30Z"));
  }

  @Test
  void graceMustBePositive() {
    assertThatThrownBy(() -> new KafkaDispatchProperties(Duration.ZERO, 3, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("nms.kafka.poller-grace must be > 0");
    assertThatThrownBy(() -> new KafkaDispatchProperties(Duration.ofSeconds(-1), 3, 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void partitionsAndConcurrencyMustBePositive() {
    assertThatThrownBy(() -> new KafkaDispatchProperties(GRACE, 0, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("nms.kafka.partitions must be > 0");
    assertThatThrownBy(() -> new KafkaDispatchProperties(GRACE, 3, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("nms.kafka.concurrency must be > 0");
  }

  @Test
  void processSendsAPendingDueDeliveryThroughTheClaim() {
    Delivery pending = row(DeliveryStatus.PENDING, NOW);
    when(deliveries.claim(pending.getId(), DeliveryStatus.PENDING, 0L, NOW)).thenReturn(1);

    assertThat(worker(Optional.empty()).process(pending.getId())).isTrue();

    verify(provider).send(any());
    verify(outcomes).sent(any(), eq("em-1"), eq(NOW));
  }

  @Test
  void processSkipsARowAlreadySentWithoutAClaim() {
    Delivery sent = row(DeliveryStatus.SENT, NOW);

    assertThat(worker(Optional.empty()).process(sent.getId())).isFalse();

    verify(deliveries, never()).claim(any(), any(), anyLong(), any());
    verify(provider, never()).send(any());
  }

  @Test
  void processSkipsARetryScheduledRow() {
    Delivery retry = row(DeliveryStatus.RETRY_SCHEDULED, NOW);

    assertThat(worker(Optional.empty()).process(retry.getId())).isFalse();

    verify(deliveries, never()).claim(any(), any(), anyLong(), any());
  }

  @Test
  void processSkipsARowNotDueYet() {
    Delivery scheduled = row(DeliveryStatus.PENDING, NOW.plusSeconds(1));

    assertThat(worker(Optional.empty()).process(scheduled.getId())).isFalse();

    verify(deliveries, never()).claim(any(), any(), anyLong(), any());
  }

  @Test
  void processSkipsAMissingRow() {
    Delivery missing = delivery(notification, Channel.EMAIL, NOW);
    when(deliveries.findById(missing.getId())).thenReturn(Optional.empty());

    assertThat(worker(Optional.empty()).process(missing.getId())).isFalse();

    verify(deliveries, never()).claim(any(), any(), anyLong(), any());
  }

  @Test
  void processDoesNotSendWhenThePollerWonTheClaim() {
    Delivery pending = row(DeliveryStatus.PENDING, NOW);
    when(deliveries.claim(pending.getId(), DeliveryStatus.PENDING, 0L, NOW)).thenReturn(0);

    assertThat(worker(Optional.empty()).process(pending.getId())).isFalse();

    verify(provider, never()).send(any());
  }

  @Test
  void processDoesNothingOnceTheWorkerIsStopping() {
    Delivery pending = row(DeliveryStatus.PENDING, NOW);
    DeliveryWorker worker = worker(Optional.empty());
    worker.stop();

    assertThat(worker.process(pending.getId())).isFalse();

    verify(deliveries, never()).findById(any());
  }

  /** A persisted EMAIL delivery in {@code status}, due at {@code dueAt}, found by id. */
  private Delivery row(DeliveryStatus status, Instant dueAt) {
    Delivery delivery = delivery(notification, Channel.EMAIL, dueAt);
    ReflectionTestUtils.setField(delivery, "version", 0L);
    ReflectionTestUtils.setField(delivery, "status", status);
    when(deliveries.findById(delivery.getId())).thenReturn(Optional.of(delivery));
    return delivery;
  }

  private DeliveryWorker worker(Optional<FirstDispatchGrace> grace) {
    return new DeliveryWorker(
        deliveries,
        notifications,
        recipients,
        providers,
        outcomes,
        new RetryPolicy(3, Duration.ofMillis(500), max -> max),
        new DeliveryMetrics(new SimpleMeterRegistry()),
        new MutableClock(NOW),
        new WorkerProperties(
            false,
            Duration.ofMillis(500),
            20,
            LEASE,
            Duration.ofSeconds(10),
            3,
            Duration.ofMillis(500)),
        RateLimiter.ALLOW_ALL,
        grace);
  }
}
