package com.example.nms.api;

import static com.example.nms.support.SubmitRequests.IDEMPOTENCY_KEY;
import static com.example.nms.support.SubmitRequests.PATH;
import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.SubmitRequests.newKey;
import static com.example.nms.support.SubmitRequests.valid;
import static com.example.nms.support.TestApiKeys.DEMO1;
import static com.example.nms.support.TestApiKeys.HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.intake.IdempotencyRecord;
import com.example.nms.intake.IdempotencyRecordRepository;
import com.example.nms.intake.Notification;
import com.example.nms.intake.RoutingStep;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #8: two concurrent submissions with one key on a real server. The first is held inside its
 * transaction (in the routing step, after it claimed the key) while the second runs into the key,
 * so the race path is exercised every time, not by chance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SubmitConcurrencyIT.GateConfiguration.class)
class SubmitConcurrencyIT {

  /** Long enough for the second request to reach the key's lock, well under H2's lock timeout. */
  private static final Duration OVERLAP = Duration.ofMillis(500);

  @LocalServerPort private int port;
  @Autowired private Gate gate;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdempotencyRecordRepository records;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void sameNewKeyCreatesOnceAndTheLoserReplaysTheWinner() throws Exception {
    assertOneCreatesAndOneReplays(newKey());
  }

  @Test
  void sameExpiredKeyIsTakenOverOnceAndTheLoserReplaysTheWinner() throws Exception {
    String key = newKey();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    records.saveAndFlush(
        new IdempotencyRecord(
            "demo-app",
            key,
            "a".repeat(64),
            UUID.randomUUID(),
            now.minus(Duration.ofDays(8)),
            now.minus(Duration.ofDays(1))));

    assertOneCreatesAndOneReplays(key);

    assertThat(jdbc.queryForObject("select count(*) from idempotency_record", Integer.class))
        .isEqualTo(1);
  }

  private void assertOneCreatesAndOneReplays(String key) throws Exception {
    String body = valid("user-ok", "user-email-only").toString();
    gate.arm();

    CompletableFuture<HttpResponse<String>> first = send(key, body);
    assertThat(gate.held.await(10, TimeUnit.SECONDS)).as("first request holds the key").isTrue();
    CompletableFuture<HttpResponse<String>> second = send(key, body);
    Thread.sleep(OVERLAP);
    assertThat(second).as("second request waits for the first").isNotDone();
    gate.release.countDown();

    HttpResponse<String> winner = first.get(10, TimeUnit.SECONDS);
    HttpResponse<String> loser = second.get(10, TimeUnit.SECONDS);

    assertThat(winner.statusCode()).isEqualTo(202);
    assertThat(loser.statusCode()).isEqualTo(200);
    assertThat(notifications(loser)).isEqualTo(notifications(winner));
    assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where type = 'DUPLICATE_SUBMISSION'",
                Integer.class))
        .isEqualTo(2);
  }

  private CompletableFuture<HttpResponse<String>> send(String key, String body) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
            .header(HEADER, DEMO1)
            .header(IDEMPOTENCY_KEY, key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode notifications(HttpResponse<String> response) {
    return JsonMapper.shared().readTree(response.body()).get("notifications");
  }

  /** Holds the first armed submission inside its transaction until released. */
  static class Gate implements RoutingStep {

    private final AtomicBoolean armed = new AtomicBoolean();
    private volatile CountDownLatch held = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);

    void arm() {
      held = new CountDownLatch(1);
      release = new CountDownLatch(1);
      armed.set(true);
    }

    @Override
    public void route(Notification notification, Instant now) {
      if (armed.compareAndSet(true, false)) {
        held.countDown();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class GateConfiguration {

    @Bean
    @Primary
    Gate gate() {
      return new Gate();
    }
  }
}
