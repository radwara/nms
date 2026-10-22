package com.example.nms.provider;

import com.example.nms.common.CallbackEvent;
import com.example.nms.observability.RedactedThrowable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simulated vendors' callbacks: after a send is first accepted, posts the outcome encoded in
 * the address to {@code POST /api/v1/webhooks/{provider}} with the provider's {@code
 * X-Webhook-Token}, after {@code delay}, over real HTTP. Runs on its own single thread, so it works
 * with the delivery worker disabled. One attempt per callback, no retry; a failure is logged by
 * status or exception type. Off unless {@code nms.simulator.callbacks.enabled}.
 */
@Component
@EnableConfigurationProperties(SimulatedCallbackProperties.class)
public class SimulatedCallbacks implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(SimulatedCallbacks.class);

  static final String TOKEN_HEADER = "X-Webhook-Token";
  static final String WEBHOOKS_PATH = "/api/v1/webhooks/";
  static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

  private final SimulatedCallbackProperties properties;
  private final Clock clock;
  private final ScheduledExecutorService executor;
  private final HttpClient http;
  private final AtomicInteger scheduled = new AtomicInteger();
  private volatile String baseUrl;

  SimulatedCallbacks(SimulatedCallbackProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
    if (!properties.enabled()) {
      this.baseUrl = null;
      this.executor = null;
      this.http = null;
      return;
    }
    this.baseUrl = validBaseUrl(properties.baseUrl());
    for (String provider : List.of(SimulatedEmailProvider.NAME, SimulatedSmsProvider.NAME)) {
      if (blankToNull(properties.tokens().get(provider)) == null) {
        throw new IllegalStateException("nms.simulator.callbacks.tokens: no token for " + provider);
      }
    }
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("simulated-callbacks").daemon().factory());
    this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    log.info(
        "Simulated provider callbacks enabled: delay {}, target {}",
        properties.delay(),
        baseUrl == null ? "this server" : baseUrl);
  }

  /** Without a configured {@code base-url}, callbacks go to this server's own port. */
  @EventListener
  void serverStarted(WebServerInitializedEvent event) {
    if (blankToNull(properties.baseUrl()) == null
        && event.getApplicationContext().getServerNamespace() == null) {
      baseUrl = "http://localhost:" + event.getWebServer().getPort();
    }
  }

  /**
   * A message was accepted for the first time; a deduped resend must not call this. Schedules its
   * callback, if callbacks are enabled and the address asks for one.
   */
  void accepted(
      String provider,
      UUID deliveryId,
      String providerMessageId,
      Optional<SimulatedCallback> callback) {
    Optional<CallbackEvent> event = callback.flatMap(SimulatedCallback::event);
    if (executor == null || event.isEmpty()) {
      return;
    }
    scheduled.incrementAndGet();
    String reasonCode = callback.get().reasonCode();
    executor.schedule(
        () -> post(provider, deliveryId, providerMessageId, event.get(), reasonCode),
        properties.delay().toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /** {@code null} if blank (this server); otherwise an absolute http(s) URL, else startup fails. */
  static String validBaseUrl(String configured) {
    String value = blankToNull(configured);
    if (value == null) {
      return null;
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException e) {
      throw invalidBaseUrl();
    }
    String scheme = uri.getScheme();
    if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
        || uri.getHost() == null) {
      throw invalidBaseUrl();
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static IllegalStateException invalidBaseUrl() {
    return new IllegalStateException(
        "nms.simulator.callbacks.base-url must be an absolute http or https URL, or blank");
  }

  /** Callbacks scheduled so far; a deduped resend does not count. */
  public int scheduledCallbacks() {
    return scheduled.get();
  }

  private void post(
      String provider,
      UUID deliveryId,
      String providerMessageId,
      CallbackEvent event,
      String reasonCode) {
    try {
      String target = baseUrl;
      if (target == null) {
        log.warn("Simulated callback for delivery {} dropped: no server port yet", deliveryId);
        return;
      }
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(target + WEBHOOKS_PATH + provider))
              .timeout(HTTP_TIMEOUT)
              .header("Content-Type", "application/json")
              .header(TOKEN_HEADER, properties.tokens().get(provider))
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      body(deliveryId, providerMessageId, event, reasonCode)))
              .build();
      int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      if (status == 200) {
        log.debug("Simulated callback {} for delivery {} posted", event, deliveryId);
      } else {
        log.warn("Simulated callback {} for delivery {} got HTTP {}", event, deliveryId, status);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException e) {
      // Anything escaping a scheduled task would be swallowed silently by the executor.
      log.warn(
          "Simulated callback {} for delivery {} failed",
          event,
          deliveryId,
          RedactedThrowable.of(e));
    }
  }

  private String body(
      UUID deliveryId, String providerMessageId, CallbackEvent event, String reasonCode) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("deliveryId", deliveryId.toString());
    body.put("providerMessageId", providerMessageId);
    body.put("event", event.name());
    if (reasonCode != null) {
      body.put("reasonCode", reasonCode);
    }
    body.put("occurredAt", clock.instant().toString());
    return JsonMapper.shared().writeValueAsString(body);
  }

  @Override
  public void destroy() {
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
