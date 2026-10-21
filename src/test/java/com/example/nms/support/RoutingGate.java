package com.example.nms.support;

import com.example.nms.intake.Notification;
import com.example.nms.intake.RoutingStep;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Routing step that holds the first armed submission inside its transaction (after it claimed the
 * key) until released, so tests can race a second request against it deterministically.
 */
public class RoutingGate implements RoutingStep {

  private final AtomicBoolean armed = new AtomicBoolean();
  private volatile CountDownLatch held = new CountDownLatch(1);
  private volatile CountDownLatch release = new CountDownLatch(1);

  public void arm() {
    held = new CountDownLatch(1);
    release = new CountDownLatch(1);
    armed.set(true);
  }

  /** Waits until the armed submission is inside the routing step. */
  public boolean awaitHeld() throws InterruptedException {
    return held.await(10, TimeUnit.SECONDS);
  }

  public void release() {
    release.countDown();
  }

  @Override
  public void route(List<Notification> notifications, Instant now) {
    if (armed.compareAndSet(true, false)) {
      held.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** POSTs a submission to a running server. */
  public static CompletableFuture<HttpResponse<String>> submit(
      HttpClient http, int port, String idempotencyKey, String body) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + SubmitRequests.PATH))
            .header(TestApiKeys.HEADER, TestApiKeys.DEMO1)
            .header(SubmitRequests.IDEMPOTENCY_KEY, idempotencyKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Configuration {

    @Bean
    @Primary
    RoutingGate routingGate() {
      return new RoutingGate();
    }
  }
}
