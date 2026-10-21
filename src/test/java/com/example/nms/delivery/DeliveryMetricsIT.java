package com.example.nms.delivery;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** #10, NFR-5: delivery metrics are scraped from {@code /actuator/prometheus}. */
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryMetricsIT {

  private final HttpClient client = HttpClient.newHttpClient();

  @LocalServerPort private int port;
  @Autowired private DeliveryWorker worker;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private NotificationRepository notifications;
  @Autowired private Clock clock;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void attemptsOutcomesAndDueAreExported() throws Exception {
    Instant now = clock.instant();
    queue("user-ok", Channel.EMAIL, null);
    queue("user-reject", Channel.SMS, null);
    queue("user-ok", Channel.EMAIL, now.minusSeconds(1));

    worker.runOnce();
    String scrape = scrape();

    assertThat(scrape)
        .contains(
            "nms_delivery_attempts_total{category=\"ACCEPTED\",channel=\"EMAIL\"} 1.0",
            "nms_delivery_attempts_total{category=\"PERMANENT_REJECTION\",channel=\"SMS\"} 1.0",
            "nms_deliveries_outcome_total{channel=\"EMAIL\",status=\"SENT\"} 1.0",
            "nms_deliveries_outcome_total{channel=\"SMS\",status=\"FAILED\"} 1.0",
            "nms_deliveries_outcome_total{channel=\"EMAIL\",status=\"EXPIRED\"} 1.0",
            "nms_deliveries_due 3.0",
            "nms_worker_poll_seconds_count");

    worker.runOnce();

    assertThat(scrape()).contains("nms_deliveries_due 0.0");
  }

  private void queue(String recipient, Channel channel, Instant expiresAt) {
    Instant now = clock.instant();
    Notification notification =
        notifications.save(
            notification(recipient, Priority.NORMAL, now.minusSeconds(10), expiresAt));
    deliveries.save(delivery(notification, channel, now));
  }

  private String scrape() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }
}
