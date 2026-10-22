package com.example.nms.delivery;

import static com.example.nms.delivery.KafkaDispatchSubmissions.published;
import static com.example.nms.delivery.KafkaDispatchSubmissions.submit;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.intake.IntakeService;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * #47 review: with the broker unreachable a publish blocks for {@code max.block.ms} (3s here), but
 * on the publish thread: concurrent submits return well before that and hold no connection while
 * the publishes are still blocked.
 */
@SpringBootTest(
    properties = {
      "nms.kafka.enabled=true",
      "spring.kafka.bootstrap-servers=localhost:1",
      "spring.kafka.producer.properties[max.block.ms]=3000",
      "spring.kafka.listener.auto-startup=false",
      "spring.kafka.admin.auto-create=false"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaBrokerDownSubmitIT {

  private static final int SUBMITS = 10;
  private static final Duration MAX_BLOCK = Duration.ofSeconds(3);

  @Autowired private IntakeService intake;
  @Autowired private HikariDataSource dataSource;
  @Autowired private MeterRegistry registry;

  @Test
  void concurrentSubmitsReturnFastAndHoldNoConnectionWhilePublishesBlock() throws Exception {
    double failedBefore = published(registry, DeliveryMetrics.PUBLISH_FAILED);
    CyclicBarrier start = new CyclicBarrier(SUBMITS);
    ExecutorService clients = Executors.newFixedThreadPool(SUBMITS);
    List<Future<Duration>> took = new ArrayList<>();
    try {
      for (int i = 0; i < SUBMITS; i++) {
        took.add(
            clients.submit(
                () -> {
                  start.await();
                  long started = System.nanoTime();
                  submit(intake, Channel.EMAIL, "user-ok");
                  return Duration.ofNanos(System.nanoTime() - started);
                }));
      }
      for (Future<Duration> submit : took) {
        assertThat(submit.get(30, TimeUnit.SECONDS)).isLessThan(MAX_BLOCK.dividedBy(3));
      }
    } finally {
      clients.shutdownNow();
    }

    // The publishes are still waiting for broker metadata, one at a time on the publish thread.
    assertThat(published(registry, DeliveryMetrics.PUBLISH_FAILED))
        .isLessThan(failedBefore + SUBMITS);
    assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
  }
}
