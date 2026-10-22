package com.example.nms.delivery;

import static com.example.nms.delivery.KafkaDispatchSubmissions.awaitSent;
import static com.example.nms.delivery.KafkaDispatchSubmissions.published;
import static com.example.nms.delivery.KafkaDispatchSubmissions.submit;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.intake.IntakeService;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;

/**
 * #47, ADR-0008: Kafka on, against an in-JVM KRaft broker, with the poller running. The grace
 * window (60s) is longer than every wait here, so an EMAIL row sent within a test was sent by the
 * consumer, not the poller.
 */
@SpringBootTest(
    properties = {
      "nms.kafka.enabled=true",
      "nms.kafka.poller-grace=60s",
      // One partition, so the duplicate test can rely on message order.
      "nms.kafka.partitions=1",
      "nms.worker.enabled=true"
    })
@EmbeddedKafka(
    partitions = 1,
    topics = KafkaDispatchConfiguration.TOPIC,
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
// Stop this context's poller and consumer once the class is done.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaEmailDispatchIT {

  private static final Duration GRACE = Duration.ofSeconds(60);
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  @Autowired private IntakeService intake;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private KafkaTemplate<String, String> kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;
  @Autowired private EmbeddedKafkaBroker broker;
  @Autowired private MeterRegistry registry;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Clock clock;
  @Autowired private DeliveryWorker worker;

  @BeforeEach
  void consumerAssigned() {
    ContainerTestUtils.waitForAssignment(
        listeners.getListenerContainer(EmailDispatchListener.ID), broker.getPartitionsPerTopic());
  }

  @Test
  void emailGoesFromIntakeThroughKafkaToSentInsideTheGraceWindow() {
    double published = published(registry, DeliveryMetrics.PUBLISHED);

    UUID id = submit(intake, Channel.EMAIL, "user-ok").getFirst();

    Delivery sent = awaitSent(deliveries, id, TIMEOUT);
    assertThat(sent.getAttemptCount()).isOne();
    assertThat(Duration.between(sent.getCreatedAt(), sent.getSentAt())).isLessThan(GRACE);
    await()
        .atMost(TIMEOUT)
        .until(() -> published(registry, DeliveryMetrics.PUBLISHED), p -> p == published + 1);
    assertThat(published(registry, DeliveryMetrics.PUBLISH_FAILED)).isZero();
  }

  /** The poller alone cannot send a fresh EMAIL row; the consumer does once it runs. */
  @Test
  void withTheConsumerStoppedThePollerLeavesTheEmailRowUntilTheConsumerStarts() {
    MessageListenerContainer consumer = listeners.getListenerContainer(EmailDispatchListener.ID);
    consumer.stop();
    try {
      UUID id = submit(intake, Channel.EMAIL, "user-ok").getFirst();
      for (int i = 0; i < 3; i++) {
        worker.runOnce();
      }
      Delivery queued = deliveries.findByNotificationIdOrderByChannel(id).getFirst();
      assertThat(queued.getStatus()).isEqualTo(DeliveryStatus.PENDING);
      assertThat(queued.getAttemptCount()).isZero();

      consumer.start();
      Delivery sent = awaitSent(deliveries, id, TIMEOUT);
      assertThat(Duration.between(sent.getCreatedAt(), sent.getSentAt())).isLessThan(GRACE);
    } finally {
      if (!consumer.isRunning()) {
        consumer.start();
      }
    }
  }

  @Test
  void theSameIdPublishedTwiceIsSentOnce() throws Exception {
    // Saved directly, not through intake, so this test publishes every message itself.
    Delivery twice = queueEmail();
    Delivery marker = queueEmail();
    double accepted = accepted();

    publish(twice.getId());
    publish(twice.getId());
    publish(marker.getId());

    // One partition, one consumer thread: both copies were handled before the marker.
    awaitSent(deliveries, marker.getNotificationId(), TIMEOUT);
    Delivery sent = deliveries.findById(twice.getId()).orElseThrow();
    assertThat(sent.getAttemptCount()).isOne();
    assertThat(sentAudits(twice.getId())).isOne();
    assertThat(accepted()).isEqualTo(accepted + 2);
  }

  @Test
  void smsIsSentByThePollerAndNeverPublished() {
    double published =
        published(registry, DeliveryMetrics.PUBLISHED)
            + published(registry, DeliveryMetrics.PUBLISH_FAILED);

    UUID id = submit(intake, Channel.SMS, "user-ok").getFirst();

    Delivery sent = awaitSent(deliveries, id, TIMEOUT);
    assertThat(sent.getChannel()).isEqualTo(Channel.SMS);
    assertThat(
            published(registry, DeliveryMetrics.PUBLISHED)
                + published(registry, DeliveryMetrics.PUBLISH_FAILED))
        .isEqualTo(published);
  }

  private Delivery queueEmail() {
    Notification notification =
        notifications.save(notification("user-ok", Priority.NORMAL, clock.instant(), null));
    return deliveries.save(delivery(notification, Channel.EMAIL, clock.instant()));
  }

  private void publish(UUID id) throws Exception {
    kafka.send(KafkaDispatchConfiguration.TOPIC, id.toString(), id.toString()).get();
  }

  private long sentAudits(UUID deliveryId) {
    return jdbc.queryForObject(
        "select count(*) from audit_event where delivery_id = ? and type = 'SENT'",
        Long.class,
        deliveryId);
  }

  private double accepted() {
    Counter counter =
        registry
            .find("nms.delivery.attempts")
            .tags("channel", "EMAIL", "category", DeliveryMetrics.ACCEPTED)
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
