package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.nms.common.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

/** #47, ADR-0008: after-commit publish of EMAIL delivery ids; a failure never propagates. */
class EmailDispatchPublisherTest {

  @SuppressWarnings("unchecked")
  private final KafkaOperations<String, String> kafka = mock(KafkaOperations.class);

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final EmailDispatchPublisher publisher =
      new EmailDispatchPublisher(kafka, new DeliveryMetrics(registry));

  private final UUID email1 = UUID.randomUUID();
  private final UUID email2 = UUID.randomUUID();
  private final UUID sms = UUID.randomUUID();

  private final DeliveriesQueued queued =
      new DeliveriesQueued(
          List.of(
              new DeliveriesQueued.Queued(email1, Channel.EMAIL),
              new DeliveriesQueued.Queued(sms, Channel.SMS),
              new DeliveriesQueued.Queued(email2, Channel.EMAIL)));

  @Test
  void publishesEachEmailIdKeyedByItselfAndSkipsOtherChannels() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));

    publisher.publish(queued);

    verify(kafka).send(KafkaDispatchConfiguration.TOPIC, email1.toString(), email1.toString());
    verify(kafka).send(KafkaDispatchConfiguration.TOPIC, email2.toString(), email2.toString());
    verify(kafka, times(2)).send(anyString(), anyString(), anyString());
    assertThat(count(DeliveryMetrics.PUBLISHED)).isEqualTo(2);
  }

  @Test
  void aSendThatFailsAtOnceStopsTheRestWhichAreCountedFailed() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new TimeoutException("no metadata")));

    publisher.publish(queued);

    verify(kafka, times(1)).send(anyString(), anyString(), anyString());
    assertThat(count(DeliveryMetrics.PUBLISH_FAILED)).isEqualTo(2);
  }

  @Test
  void aThrowingSendIsCountedAndSwallowed() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenThrow(new KafkaException("producer closed"));

    publisher.publish(queued);

    assertThat(count(DeliveryMetrics.PUBLISH_FAILED)).isEqualTo(2);
  }

  @Test
  void aLaterSendFailureIsCountedWhenItCompletes() {
    CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
    when(kafka.send(anyString(), anyString(), anyString())).thenReturn(pending);

    publisher.publish(queued);
    pending.completeExceptionally(new TimeoutException("delivery timeout"));

    verify(kafka, times(2)).send(anyString(), anyString(), anyString());
    assertThat(count(DeliveryMetrics.PUBLISH_FAILED)).isEqualTo(2);
  }

  @Test
  void noEmailNoSend() {
    publisher.publish(new DeliveriesQueued(List.of(new DeliveriesQueued.Queued(sms, Channel.SMS))));

    verify(kafka, never()).send(anyString(), any(), any());
  }

  private double count(String outcome) {
    Counter counter = registry.find("nms.kafka.published").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }
}
