package com.example.nms.delivery;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Sends due deliveries (ADR-0004). One poll reads each due status separately, merges by priority
 * then due time, and works the best {@code batch-size} rows one by one: expire, or claim
 * (committed) then send outside any transaction then write the outcome (conditional on the claim's
 * version). Each attempt runs under its own trace id. Addresses and content are never logged.
 */
@Component
public class DeliveryWorker {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

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
    this.deliveries = deliveries;
    this.notifications = notifications;
    this.recipients = recipients;
    this.providers = providers;
    this.outcomes = outcomes;
    this.retryPolicy = retryPolicy;
    this.metrics = metrics;
    this.clock = clock;
    this.properties = properties;
  }

  /**
   * One poll: works up to {@code batch-size} due deliveries and returns how many it finished an
   * attempt or expiry for. Not thread-safe by design; one single-threaded poller calls it.
   */
  public int runOnce() {
    List<Delivery> due = poll(clock.instant());
    metrics.due(due.size());
    if (due.isEmpty()) {
      return 0;
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
      Notification notification = notificationsById.get(delivery.getNotificationId());
      TraceIds.Scope scope = TraceIds.open(TraceIds.newId());
      try {
        if (work(delivery, notification, recipientsById.get(notification.getRecipientId()))) {
          worked++;
        }
      } catch (RuntimeException e) {
        // A row left SENDING is reclaimed after the lease; others stay due.
        log.error("Delivery {} not processed", delivery.getId(), RedactedThrowable.of(e));
      } finally {
        scope.close();
      }
    }
    return worked;
  }

  /** Due rows of every due status, best first: one query per status, merged here. */
  private List<Delivery> poll(Instant now) {
    Limit limit = Limit.of(properties.batchSize());
    List<Delivery> due = new ArrayList<>();
    due.addAll(deliveries.findDue(DeliveryStatus.PENDING, now, limit));
    due.addAll(deliveries.findDue(DeliveryStatus.RETRY_SCHEDULED, now, limit));
    due.addAll(deliveries.findLeaseExpired(now, now.minus(properties.lease()), limit));
    due.sort(BEST_FIRST);
    return due;
  }

  private boolean work(Delivery delivery, Notification notification, Recipient recipient) {
    Instant now = clock.instant();
    if (isExpired(notification, now)) {
      return expire(delivery, notification, now);
    }
    ChannelProvider provider =
        providers
            .find(delivery.getProvider())
            .orElseThrow(
                () -> new IllegalStateException("No provider adapter " + delivery.getProvider()));
    SendRequest request =
        new SendRequest(
            delivery.getId(),
            address(delivery.getChannel(), recipient),
            notification.getSubject(),
            notification.getBody());

    if (deliveries.claim(delivery.getId(), delivery.getStatus(), delivery.getVersion(), now) == 0) {
      log.debug("Delivery {} claimed by another worker", delivery.getId());
      return false;
    }
    Claim claim = Claim.of(delivery, notification);
    SendResult result = provider.send(request);
    return switch (result) {
      case Accepted accepted -> sent(claim, accepted);
      case Failed failed -> failed(claim, failed);
    };
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

  private static String address(Channel channel, Recipient recipient) {
    String address =
        switch (channel) {
          case EMAIL -> recipient.getEmail();
          case SMS -> recipient.getPhone();
        };
    if (address == null) {
      // Routing only selects channels with an address, and the directory is read-only.
      throw new IllegalStateException("Recipient " + recipient.getId() + " has no " + channel);
    }
    return address;
  }

  private static <T, K> Set<K> ids(Collection<T> items, Function<T, K> id) {
    return items.stream().map(id).collect(Collectors.toSet());
  }

  private static <T, K> Map<K, T> byId(List<T> items, Function<T, K> id) {
    return items.stream().collect(Collectors.toMap(id, Function.identity()));
  }
}
