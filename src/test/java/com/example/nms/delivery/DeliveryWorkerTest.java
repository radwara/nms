package com.example.nms.delivery;

import static com.example.nms.support.TestEntities.NOW;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** #10: stopping between deliveries, and pre-claim errors that must not stall the queue. */
class DeliveryWorkerTest {

  private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
  private final NotificationRepository notifications = mock(NotificationRepository.class);
  private final RecipientRepository recipients = mock(RecipientRepository.class);
  private final ProviderRegistry providers = mock(ProviderRegistry.class);
  private final DeliveryOutcomes outcomes = mock(DeliveryOutcomes.class);
  private final ChannelProvider provider = mock(ChannelProvider.class);
  private final MutableClock clock = new MutableClock(NOW);

  private final DeliveryWorker worker =
      new DeliveryWorker(
          deliveries,
          notifications,
          recipients,
          providers,
          outcomes,
          new RetryPolicy(4, Duration.ofSeconds(2), max -> max),
          new DeliveryMetrics(new SimpleMeterRegistry()),
          clock,
          new WorkerProperties(
              false,
              Duration.ofMillis(500),
              20,
              Duration.ofSeconds(30),
              Duration.ofSeconds(10),
              4,
              Duration.ofSeconds(2)));

  private Delivery first;

  @BeforeEach
  void twoDueDeliveries() {
    Notification notification = notification("user-ok", Priority.NORMAL, NOW, null);
    first = polled(notification);
    Delivery second = polled(notification);
    when(deliveries.findDue(eq(DeliveryStatus.PENDING), any(), any()))
        .thenReturn(List.of(first, second));
    when(notifications.findAllById(any())).thenReturn(List.of(notification));
    Recipient recipient = mock(Recipient.class);
    when(recipient.getId()).thenReturn("user-ok");
    when(recipient.getEmail()).thenReturn("ok.delivered@sim.test");
    when(recipients.findAllById(any())).thenReturn(List.of(recipient));
    when(providers.find("sim-email")).thenReturn(Optional.of(provider));
    when(provider.name()).thenReturn("sim-email");
    when(deliveries.claim(any(), any(), anyLong(), any())).thenReturn(1);
    when(outcomes.sent(any(), any(), any())).thenReturn(true);
  }

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  void stopEndsThePollAfterTheDeliveryInProgress() {
    when(provider.send(any()))
        .thenAnswer(
            invocation -> {
              worker.stop();
              return new Accepted("em-1");
            });

    assertThat(worker.runOnce()).isOne();

    verify(provider, times(1)).send(any());
    verify(deliveries, times(1)).claim(any(), any(), anyLong(), any());
    assertThat(worker.runOnce()).isZero();
  }

  @Test
  void interruptEndsThePollAfterTheDeliveryInProgress() {
    when(provider.send(any()))
        .thenAnswer(
            invocation -> {
              Thread.currentThread().interrupt();
              return new Accepted("em-1");
            });

    assertThat(worker.runOnce()).isOne();

    verify(provider, times(1)).send(any());
  }

  @Test
  void unexpectedErrorBeforeTheClaimPostponesTheRowAndTheBatchGoesOn() {
    when(deliveries.claim(eq(first.getId()), any(), anyLong(), any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(provider.send(any())).thenReturn(new Accepted("em-2"));

    assertThat(worker.runOnce()).isOne();

    verify(deliveries).postpone(first.getId(), DeliveryStatus.PENDING, 0L, NOW.plusSeconds(2), NOW);
    verify(provider, times(1)).send(any());
  }

  /** A delivery as the poll returns it: persisted, version 0. */
  private static Delivery polled(Notification notification) {
    Delivery delivery = delivery(notification, Channel.EMAIL, NOW);
    ReflectionTestUtils.setField(delivery, "version", 0L);
    return delivery;
  }
}
