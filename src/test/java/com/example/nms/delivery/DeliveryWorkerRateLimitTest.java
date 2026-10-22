package com.example.nms.delivery;

import static com.example.nms.support.TestEntities.NOW;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** #46: a slot taken for a claim that is lost or throws is given back. One EMAIL per window. */
class DeliveryWorkerRateLimitTest {

  private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
  private final NotificationRepository notifications = mock(NotificationRepository.class);
  private final RecipientRepository recipients = mock(RecipientRepository.class);
  private final ProviderRegistry providers = mock(ProviderRegistry.class);
  private final DeliveryOutcomes outcomes = mock(DeliveryOutcomes.class);
  private final ChannelProvider provider = mock(ChannelProvider.class);

  private final DeliveryWorker worker =
      new DeliveryWorker(
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
              Duration.ofSeconds(30),
              Duration.ofSeconds(10),
              3,
              Duration.ofMillis(500)),
          new FixedWindowRateLimiter(
              new RateLimitProperties(Duration.ofMinutes(1), Map.of(Channel.EMAIL, 1), 1)));

  private Delivery first;
  private Delivery second;

  @BeforeEach
  void twoDueDeliveriesOfOneSource() {
    Notification notification = notification("user-ok", Priority.NORMAL, NOW, null);
    // first sorts before second (due earlier); ties would fall back to the random id.
    first = polled(notification, NOW.minusSeconds(1));
    second = polled(notification, NOW);
    when(deliveries.findDue(eq(DeliveryStatus.PENDING), eq(Priority.NORMAL), any(), any()))
        .thenReturn(List.of(first, second));
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
  void aLostClaimGivesItsSlotBack() {
    when(deliveries.claim(eq(first.getId()), any(), anyLong(), any())).thenReturn(0);
    when(deliveries.claim(eq(second.getId()), any(), anyLong(), any())).thenReturn(1);

    assertThat(worker.runOnce()).isOne();

    verify(provider, times(1)).send(any());
    verify(deliveries, never()).postponeDue(any(), any(), any(), any(), any());
  }

  @Test
  void aClaimThatThrowsGivesItsSlotBack() {
    when(deliveries.claim(eq(first.getId()), any(), anyLong(), any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(deliveries.claim(eq(second.getId()), any(), anyLong(), any())).thenReturn(1);

    assertThat(worker.runOnce()).isOne();

    verify(provider, times(1)).send(any());
    verify(deliveries, never()).postponeDue(any(), any(), any(), any(), any());
  }

  @Test
  void withoutAReleaseTheSecondRowWouldBeDeferred() {
    when(deliveries.claim(any(), any(), anyLong(), any())).thenReturn(1);

    assertThat(worker.runOnce()).isOne();

    verify(deliveries, times(1))
        .postponeDue(eq("billing"), eq(Channel.EMAIL), eq(NOW), eq(NOW.minusSeconds(30)), any());
  }

  /** A delivery as the poll returns it: persisted, version 0. */
  private static Delivery polled(Notification notification, Instant dueAt) {
    Delivery delivery = delivery(notification, Channel.EMAIL, dueAt);
    ReflectionTestUtils.setField(delivery, "version", 0L);
    return delivery;
  }
}
