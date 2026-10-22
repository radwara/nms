package com.example.nms.delivery;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.directory.Recipient;
import com.example.nms.directory.RecipientRepository;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import com.example.nms.observability.RedactedThrowable;
import com.example.nms.observability.TraceIds;
import com.example.nms.provider.ChannelProvider;
import com.example.nms.provider.ErrorCategory;
import com.example.nms.provider.ProviderRegistry;
import com.example.nms.provider.SendRequest;
import com.example.nms.provider.SendResult;
import com.example.nms.provider.SendResult.Accepted;
import com.example.nms.provider.SendResult.Failed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Sends due deliveries (ADR-0004). One poll reads each due status separately, merges by priority
 * then due time, and works the best {@code batch-size} rows one by one: expire or fail a row that
 * cannot be sent (no claim), or claim (committed) then send outside any transaction then write the
 * outcome (conditional on the claim's version). Each row runs under its own trace id. Addresses and
 * content are never logged.
 */
@Component
public class DeliveryWorker {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

  /** Failure reasons for a row that cannot be sent; it fails without a claim. */
  static final String MISSING_NOTIFICATION = "MISSING_NOTIFICATION";

  static final String MISSING_RECIPIENT = "MISSING_RECIPIENT";
  static final String NO_PROVIDER_ADAPTER = "NO_PROVIDER_ADAPTER";
  static final String NO_ADDRESS = "NO_ADDRESS";

  /** Provider code recorded when an adapter throws instead of returning a result. */
  static final String PROVIDER_EXCEPTION_CODE = "exception";

  private static final List<DeliveryStatus> DUE_STATUSES =
      List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRY_SCHEDULED);

  private static final Comparator<Delivery> BEST_FIRST =
      Comparator.comparing((Delivery d) -> d.getPriority().code())
          .thenComparing(Delivery::getNextAttemptAt)
          .thenComparing(Delivery::getId);

  private final DeliveryRepository deliveries;
  private final NotificationRepository notifications;
  private final RecipientRepository recipients;
  private final ProviderRegistry providers;
  private final DeliveryOutcomes outcomes;
  private final RetryPolicy retryPolicy;
  private final DeliveryMetrics metrics;
  private final Clock clock;
  private final WorkerProperties properties;
  private final RateLimiter rateLimiter;
  private volatile boolean stopping;

  /** Without a rate limit. */
  DeliveryWorker(
      DeliveryRepository deliveries,
      NotificationRepository notifications,
      RecipientRepository recipients,
      ProviderRegistry providers,
      DeliveryOutcomes outcomes,
      RetryPolicy retryPolicy,
      DeliveryMetrics metrics,
      Clock clock,
      WorkerProperties properties) {
    this(
        deliveries,
        notifications,
        recipients,
        providers,
        outcomes,
        retryPolicy,
        metrics,
        clock,
        properties,
        RateLimiter.ALLOW_ALL);
  }

  @Autowired
  DeliveryWorker(
      DeliveryRepository deliveries,
      NotificationRepository notifications,
      RecipientRepository recipients,
      ProviderRegistry providers,
      DeliveryOutcomes outcomes,
      RetryPolicy retryPolicy,
      DeliveryMetrics metrics,
      Clock clock,
      WorkerProperties properties,
      RateLimiter rateLimiter) {
    this.deliveries = deliveries;
    this.notifications = notifications;
    this.recipients = recipients;
    this.providers = providers;
    this.outcomes = outcomes;
    this.retryPolicy = retryPolicy;
    this.metrics = metrics;
    this.clock = clock;
    this.properties = properties;
    this.rateLimiter = rateLimiter;
  }

  /**
   * One poll: works up to {@code batch-size} due deliveries and returns how many it finished an
   * attempt or a final state for. Stops between deliveries once the worker is stopping or the
   * thread is interrupted; the rest stay due. Not thread-safe by design; one single-threaded poller
   * calls it.
   */
  public int runOnce() {
    return metrics.timePoll(this::pollOnce).worked();
  }

  /**
   * Polls again at once while a poll saw a full batch due, for at most {@code budget}, so a backlog
   * drains at send speed rather than one batch per poll interval.
   */
  public void drain(Duration budget) {
    long deadline = System.nanoTime() + budget.toNanos();
    while (metrics.timePoll(this::pollOnce).due() >= properties.batchSize()
        && !stopping()
        && System.nanoTime() < deadline) {
      log.debug("Full batch due; polling again");
    }
  }

  private Poll pollOnce() {
    if (stopping()) {
      return new Poll(0, 0);
    }
    List<Delivery> due = poll(clock.instant());
    metrics.due(due.size());
    if (due.isEmpty()) {
      return new Poll(0, 0);
    }
    List<Delivery> batch = due.subList(0, Math.min(properties.batchSize(), due.size()));
    Map<UUID, Notification> notificationsById =
        byId(
            notifications.findAllById(ids(batch, Delivery::getNotificationId)),
            Notification::getId);
    Map<String, Recipient> recipientsById =
        byId(
            recipients.findAllById(ids(notificationsById.values(), Notification::getRecipientId)),
            Recipient::getId);

    int worked = 0;
    for (Delivery delivery : batch) {
      if (stopping()) {
        log.info("Worker stopping; the rest of the batch stays due");
        break;
      }
      TraceIds.Scope scope = TraceIds.open(TraceIds.newId());
      try {
        if (work(delivery, notificationsById, recipientsById)) {
          worked++;
        }
      } finally {
        scope.close();
      }
    }
    return new Poll(due.size(), worked);
  }

  private boolean stopping() {
    return stopping || Thread.currentThread().isInterrupted();
  }

  /** Rows a poll saw due (before the batch cut) and rows it finished. */
  private record Poll(int due, int worked) {}

  /** Stops {@link #runOnce()} after the delivery in progress; called when the context closes. */
  @EventListener(ContextClosedEvent.class)
  public void stop() {
    stopping = true;
  }

  /**
   * Due rows, best first: one query per due status and priority, plus reclaimable leases, merged
   * here by priority then due time.
   */
  private List<Delivery> poll(Instant now) {
    Limit limit = Limit.of(properties.batchSize());
    List<Delivery> due = new ArrayList<>();
    for (DeliveryStatus status : DUE_STATUSES) {
      for (Priority priority : Priority.values()) {
        due.addAll(deliveries.findDue(status, priority, now, limit));
      }
    }
    due.addAll(deliveries.findLeaseExpired(now, now.minus(properties.lease()), limit));
    due.sort(BEST_FIRST);
    return due;
  }

  /**
   * Before the claim, every failure ends or moves the row (conditional on the polled version), so
   * no row can keep sorting first and stall the queue. After the claim, a row whose outcome cannot
   * be written stays {@code SENDING} and is reclaimed after the lease.
   */
  private boolean work(
      Delivery delivery,
      Map<UUID, Notification> notificationsById,
      Map<String, Recipient> recipientsById) {
    Instant now = clock.instant();
    Step step;
    try {
      step = prepareAndClaim(delivery, notificationsById, recipientsById, now);
    } catch (RuntimeException e) {
      postpone(delivery, now, e);
      return false;
    }
    if (!(step instanceof Step.Claimed claimed)) {
      return ((Step.Done) step).written();
    }
    try {
      SendResult result = send(claimed.provider(), claimed.request(), claimed.claim());
      return switch (result) {
        case Accepted accepted -> sent(claimed.claim(), accepted);
        case Failed failed -> failed(claimed.claim(), failed);
      };
    } catch (RuntimeException e) {
      log.error(
          "Delivery {} outcome not written; resent after the lease",
          delivery.getId(),
          RedactedThrowable.of(e));
      return false;
    }
  }

  private Step prepareAndClaim(
      Delivery delivery,
      Map<UUID, Notification> notificationsById,
      Map<String, Recipient> recipientsById,
      Instant now) {
    Notification notification = notificationsById.get(delivery.getNotificationId());
    if (notification == null) {
      return failBeforeSend(delivery, null, MISSING_NOTIFICATION, now);
    }
    if (isExpired(notification, now)) {
      return new Step.Done(expire(delivery, notification, now));
    }
    if (retryPolicy.exhausted(delivery.getAttemptCount())) {
      return failBeforeSend(delivery, notification, RetryPolicy.RETRIES_EXHAUSTED, now);
    }
    Recipient recipient = recipientsById.get(notification.getRecipientId());
    if (recipient == null) {
      return failBeforeSend(delivery, notification, MISSING_RECIPIENT, now);
    }
    Optional<ChannelProvider> provider = providers.find(delivery.getProvider());
    if (provider.isEmpty()) {
      return failBeforeSend(delivery, notification, NO_PROVIDER_ADAPTER, now);
    }
    String address = address(delivery.getChannel(), recipient);
    if (address == null) {
      return failBeforeSend(delivery, notification, NO_ADDRESS, now);
    }
    Optional<Instant> retryAt =
        rateLimiter.acquire(notification.getSourceSystem(), delivery.getChannel(), now);
    if (retryAt.isPresent()) {
      return new Step.Done(defer(delivery, retryAt.get(), now));
    }
    if (deliveries.claim(delivery.getId(), delivery.getStatus(), delivery.getVersion(), now) == 0) {
      log.debug("Delivery {} claimed by another worker", delivery.getId());
      return new Step.Done(false);
    }
    return new Step.Claimed(
        Claim.of(delivery, notification),
        provider.get(),
        new SendRequest(
            delivery.getId(), address, notification.getSubject(), notification.getBody()));
  }

  /** The adapter contract says it returns; a throw is a failed call like any other. */
  private static SendResult send(ChannelProvider provider, SendRequest request, Claim claim) {
    try {
      return provider.send(request);
    } catch (RuntimeException e) {
      log.warn(
          "Provider {} threw on delivery {}; treated as TRANSIENT",
          provider.name(),
          claim.id(),
          RedactedThrowable.of(e));
      return new Failed(ErrorCategory.TRANSIENT, PROVIDER_EXCEPTION_CODE);
    }
  }

  private Step failBeforeSend(
      Delivery delivery, Notification notification, String reason, Instant now) {
    boolean written = outcomes.failedBeforeSend(delivery, notification, reason, now);
    if (written) {
      metrics.outcome(delivery.getChannel(), DeliveryStatus.FAILED);
      if (RetryPolicy.RETRIES_EXHAUSTED.equals(reason)) {
        log.warn(
            "Delivery {} on {} failed before send: {}",
            delivery.getId(),
            delivery.getChannel(),
            reason);
      } else {
        log.error(
            "Delivery {} on {} failed before send: {}",
            delivery.getId(),
            delivery.getChannel(),
            reason);
      }
    }
    return new Step.Done(written);
  }

  /** Pushes the row's due time forward so it stops sorting first; the error is logged by type. */
  private void postpone(Delivery delivery, Instant now, RuntimeException cause) {
    Instant next = now.plus(retryPolicy.postponement(delivery.getAttemptCount()));
    log.error(
        "Delivery {} not processed; postponed to {}",
        delivery.getId(),
        next,
        RedactedThrowable.of(cause));
    try {
      deliveries.postpone(delivery.getId(), delivery.getStatus(), delivery.getVersion(), next, now);
    } catch (RuntimeException e) {
      log.error("Delivery {} could not be postponed", delivery.getId(), RedactedThrowable.of(e));
    }
  }

  /**
   * Moves the row's due time to {@code until} without a claim: status and attempts unchanged, no
   * audit (not a state change). Returns {@code false}: no attempt or final state was written.
   */
  private boolean defer(Delivery delivery, Instant until, Instant now) {
    int moved =
        deliveries.postpone(
            delivery.getId(), delivery.getStatus(), delivery.getVersion(), until, now);
    log.debug(
        "Delivery {} on {} rate limited; {} to {}",
        delivery.getId(),
        delivery.getChannel(),
        moved == 1 ? "deferred" : "row changed meanwhile, not deferred",
        until);
    return false;
  }

  private boolean expire(Delivery delivery, Notification notification, Instant now) {
    if (!outcomes.expiredBeforeSend(delivery, notification, now)) {
      return false;
    }
    metrics.outcome(delivery.getChannel(), DeliveryStatus.EXPIRED);
    log.info("Delivery {} on {} expired before send", delivery.getId(), delivery.getChannel());
    return true;
  }

  private boolean sent(Claim claim, Accepted accepted) {
    metrics.attempt(claim.channel(), DeliveryMetrics.ACCEPTED);
    boolean written = outcomes.sent(claim, accepted.providerMessageId(), clock.instant());
    logAttempt(claim, DeliveryMetrics.ACCEPTED, written ? DeliveryStatus.SENT : null);
    if (written) {
      metrics.outcome(claim.channel(), DeliveryStatus.SENT);
    }
    return written;
  }

  private boolean failed(Claim claim, Failed failure) {
    ErrorCategory category = failure.category();
    metrics.attempt(claim.channel(), category.name());
    if (category == ErrorCategory.AUTH_ERROR) {
      log.error(
          "Provider {} rejected our credentials (code {}); check its configuration. Delivery {}",
          claim.delivery().getProvider(),
          failure.providerCode(),
          claim.id());
    }
    Instant now = clock.instant();
    DeliveryStatus status =
        switch (retryPolicy.after(
            claim.attempt(), failure, now, claim.notification().getExpiresAt())) {
          case RetryPolicy.Next.Retry retry ->
              outcomes.retry(claim, failure, retry.at(), retry.delay(), now)
                  ? DeliveryStatus.RETRY_SCHEDULED
                  : null;
          case RetryPolicy.Next.Fail fail ->
              outcomes.failed(claim, failure, fail.reason(), now) ? DeliveryStatus.FAILED : null;
          case RetryPolicy.Next.Expire expire ->
              outcomes.expiredAfterAttempt(claim, failure, now) ? DeliveryStatus.EXPIRED : null;
        };
    logAttempt(claim, category.name(), status);
    if (status == DeliveryStatus.FAILED || status == DeliveryStatus.EXPIRED) {
      metrics.outcome(claim.channel(), status);
    }
    return status != null;
  }

  /** {@code status} is the state written, or {@code null} if the claim was lost meanwhile. */
  private static void logAttempt(Claim claim, String result, DeliveryStatus status) {
    if (status == null) {
      log.info(
          "Delivery {} attempt {} on {}: {}; row changed meanwhile, outcome not written",
          claim.id(),
          claim.attempt(),
          claim.channel(),
          result);
    } else {
      log.info(
          "Delivery {} attempt {} on {}: {} -> {}",
          claim.id(),
          claim.attempt(),
          claim.channel(),
          result,
          status);
    }
  }

  private static boolean isExpired(Notification notification, Instant now) {
    Instant expiresAt = notification.getExpiresAt();
    return expiresAt != null && !now.isBefore(expiresAt);
  }

  /** The recipient's address for {@code channel}, or {@code null}. */
  private static String address(Channel channel, Recipient recipient) {
    return switch (channel) {
      case EMAIL -> recipient.getEmail();
      case SMS -> recipient.getPhone();
    };
  }

  /** What happened before the send. */
  private sealed interface Step {

    /** Handled without a send; {@code written} if this worker wrote the row. */
    record Done(boolean written) implements Step {}

    record Claimed(Claim claim, ChannelProvider provider, SendRequest request) implements Step {}
  }

  private static <T, K> Set<K> ids(Collection<T> items, Function<T, K> id) {
    return items.stream().map(id).collect(Collectors.toSet());
  }

  private static <T, K> Map<K, T> byId(List<T> items, Function<T, K> id) {
    return items.stream().collect(Collectors.toMap(id, Function.identity()));
  }
}
