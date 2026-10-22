package com.example.nms.support;

import com.example.nms.intake.Notification;
import com.example.nms.intake.RoutingStep;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real routing (deliveries, audits, routed flag), then optionally holds the first armed submission
 * inside its transaction until released, or makes the commit fail. Unlike {@link RoutingGate}, the
 * notifications it routes anchor event dedup.
 */
public class DelegatingRoutingGate implements RoutingStep {

  private final RoutingStep delegate;
  private final AtomicBoolean armed = new AtomicBoolean();
  private volatile CountDownLatch held = new CountDownLatch(1);
  private volatile CountDownLatch release = new CountDownLatch(1);
  private volatile boolean failCommit;

  public DelegatingRoutingGate(RoutingStep delegate) {
    this.delegate = delegate;
  }

  public void arm() {
    held = new CountDownLatch(1);
    release = new CountDownLatch(1);
    armed.set(true);
  }

  public boolean awaitHeld() throws InterruptedException {
    return held.await(10, TimeUnit.SECONDS);
  }

  public void release() {
    release.countDown();
  }

  public void failCommit(boolean fail) {
    failCommit = fail;
  }

  @Override
  public void route(List<Notification> notifications, Instant now) {
    delegate.route(notifications, now);
    if (failCommit) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
              throw new IllegalStateException("commit failed");
            }
          });
    }
    if (armed.compareAndSet(true, false)) {
      held.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Configuration {

    @Bean
    @Primary
    DelegatingRoutingGate delegatingRoutingGate(@Qualifier("channelRouter") RoutingStep router) {
      return new DelegatingRoutingGate(router);
    }
  }
}
