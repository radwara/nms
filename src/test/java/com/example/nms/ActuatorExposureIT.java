package com.example.nms;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * #6: health is up; only health, info, and prometheus are exposed; H2 console is off by default.
 */
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorExposureIT {

  private final HttpClient client = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  @Test
  void healthIsUp() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void prometheusIsExposed() throws Exception {
    assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(200);
  }

  @Test
  void otherActuatorEndpointsAreNotExposed() throws Exception {
    assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    assertThat(get("/actuator/beans").statusCode()).isEqualTo(404);
  }

  @Test
  void h2ConsoleIsOffByDefault() throws Exception {
    assertThat(get("/h2-console/").statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
