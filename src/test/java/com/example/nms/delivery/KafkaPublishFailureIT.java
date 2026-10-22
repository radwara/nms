package com.example.nms.delivery;

import static com.example.nms.delivery.KafkaDispatchSubmissions.awaitSent;
import static com.example.nms.delivery.KafkaDispatchSubmissions.published;
import static com.example.nms.delivery.KafkaDispatchSubmissions.submit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.nms.common.Channel;
import com.example.nms.intake.IntakeService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * #47, ADR-0008: Kafka on, but nothing listens at the bootstrap address, so every publish fails.
 * The submission is still accepted and the poller sends the rows once the grace window (2s) has
 * passed. The consumer is not started.
 */
@SpringBootTest(
    properties = {
      "nms.kafka.enabled=true",
      "nms.kafka.poller-grace=2s",
      "nms.worker.enabled=true",
      "spring.kafka.bootstrap-servers=localhost:1",
      "spring.kafka.producer.properties[max.block.ms]=200",
      "spring.kafka.listener.auto-startup=false"
    })
// Stop this context's poller once the class is done.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaPublishFailureIT {

  private static final Duration GRACE = Duration.ofSeconds(2);
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  @Autowired private IntakeService intake;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private MeterRegistry registry;

  @Test
  void aFailedPublishIsSentByThePollerAfterTheGraceWindow() {
    double failed = published(registry, DeliveryMetrics.PUBLISH_FAILED);

    List<UUID> ids = submit(intake, Channel.EMAIL, "user-ok", "user-email-only");

    await()
        .atMost(TIMEOUT)
        .until(() -> published(registry, DeliveryMetrics.PUBLISH_FAILED), f -> f == failed + 2);
    for (UUID id : ids) {
      Delivery sent = awaitSent(deliveries, id, TIMEOUT);
      assertThat(sent.getAttemptCount()).isOne();
      assertThat(sent.getSentAt()).isAfterOrEqualTo(sent.getCreatedAt().plus(GRACE));
    }
    assertThat(published(registry, DeliveryMetrics.PUBLISHED)).isZero();
  }
}
