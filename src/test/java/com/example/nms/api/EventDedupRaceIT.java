package com.example.nms.api;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.SubmitRequests.newKey;
import static com.example.nms.support.SubmitRequests.valid;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.support.DelegatingRoutingGate;
import com.example.nms.support.RoutingGate;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #14, ADR-0006: pins the documented race on a real server with real routing. Two submissions of
 * the same event and message under different keys that overlap in time are both accepted (fails
 * open: one extra message). Once the first has committed, a third is suppressed. If a later change
 * serializes them, this test shows it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DelegatingRoutingGate.Configuration.class)
class EventDedupRaceIT {

  @LocalServerPort private int port;
  @Autowired private DelegatingRoutingGate gate;
  @Autowired private JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void concurrentRepeatsUnderDifferentKeysAreBothAccepted() throws Exception {
    String body = valid("user-ok").put("eventId", "evt-race").toString();
    gate.arm();

    CompletableFuture<HttpResponse<String>> first = RoutingGate.submit(http, port, newKey(), body);
    assertThat(gate.awaitHeld()).as("first submission is inside its transaction").isTrue();
    HttpResponse<String> second =
        RoutingGate.submit(http, port, newKey(), body).get(10, TimeUnit.SECONDS);
    gate.release();
    HttpResponse<String> held = first.get(10, TimeUnit.SECONDS);
    HttpResponse<String> third =
        RoutingGate.submit(http, port, newKey(), body).get(10, TimeUnit.SECONDS);

    assertThat(second.statusCode()).isEqualTo(202);
    assertThat(held.statusCode()).isEqualTo(202);
    assertThat(second.body()).contains("\"ACCEPTED\"").doesNotContain("SUPPRESSED");
    assertThat(held.body()).contains("\"ACCEPTED\"").doesNotContain("SUPPRESSED");
    assertThat(third.statusCode()).isEqualTo(202);
    assertThat(third.body()).contains("\"SUPPRESSED\"");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notification where duplicate_of is null and routed",
                Integer.class))
        .isEqualTo(2);
  }
}
