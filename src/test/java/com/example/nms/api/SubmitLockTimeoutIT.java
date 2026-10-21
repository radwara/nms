package com.example.nms.api;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.SubmitRequests.newKey;
import static com.example.nms.support.SubmitRequests.valid;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.intake.IdempotencyRecord;
import com.example.nms.intake.IdempotencyRecordRepository;
import com.example.nms.support.RoutingGate;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #8: the first request holds the key longer than the database lock timeout (200 ms here). The
 * second must answer 409 / 4092 with {@code Retry-After}, never 5xx, and a retry after the first
 * commits replays it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
            + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=200")
@Import(RoutingGate.Configuration.class)
@ExtendWith(OutputCaptureExtension.class)
class SubmitLockTimeoutIT {

  @LocalServerPort private int port;
  @Autowired private RoutingGate gate;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdempotencyRecordRepository records;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void newKeyStillLockedIs4092ThenReplays(CapturedOutput output) throws Exception {
    assertInProgressThenReplay(newKey(), output);
  }

  @Test
  void expiredKeyStillLockedIs4092ThenReplays(CapturedOutput output) throws Exception {
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

    assertInProgressThenReplay(key, output);
  }

  private void assertInProgressThenReplay(String key, CapturedOutput output) throws Exception {
    String body = valid("user-ok").toString();
    gate.arm();
    CompletableFuture<HttpResponse<String>> first = RoutingGate.submit(http, port, key, body);
    assertThat(gate.awaitHeld()).isTrue();

    // The first request is held until the second has answered: lock wait plus retries run out.
    HttpResponse<String> second =
        RoutingGate.submit(http, port, key, body).get(10, TimeUnit.SECONDS);
    gate.release();
    HttpResponse<String> winner = first.get(10, TimeUnit.SECONDS);

    assertThat(second.statusCode()).isEqualTo(409);
    assertThat(second.headers().firstValue("Retry-After")).hasValue("1");
    assertThat(second.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    JsonNode error = JsonMapper.shared().readTree(second.body()).get("errors").get(0);
    assertThat(error.get("code").asInt()).isEqualTo(4092);
    assertThat(error.get("field").stringValue()).isEqualTo("Idempotency-Key");

    assertThat(winner.statusCode()).isEqualTo(202);
    HttpResponse<String> retry =
        RoutingGate.submit(http, port, key, body).get(10, TimeUnit.SECONDS);
    assertThat(retry.statusCode()).isEqualTo(200);
    assertThat(JsonMapper.shared().readTree(retry.body()))
        .isEqualTo(JsonMapper.shared().readTree(winner.body()));
    assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class))
        .isEqualTo(1);
    // Database errors quote the duplicate key; they must not reach the logs.
    assertThat(output.getAll()).doesNotContain(key);
  }
}
