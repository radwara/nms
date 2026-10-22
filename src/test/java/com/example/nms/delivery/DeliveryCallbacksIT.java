package com.example.nms.delivery;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static com.example.nms.support.TestEntities.NOW;
import static com.example.nms.support.TestEntities.delivery;
import static com.example.nms.support.TestEntities.notification;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.audit.AuditRecorder;
import com.example.nms.common.CallbackEvent;
import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import com.example.nms.delivery.DeliveryCallbacks.Result;
import com.example.nms.intake.Notification;
import com.example.nms.intake.NotificationRepository;
import com.example.nms.provider.ChannelProvider;
import com.example.nms.provider.SendRequest;
import com.example.nms.provider.SendResult;
import com.example.nms.provider.SendResult.Accepted;
import com.example.nms.provider.SimulatedEmailProvider;
import com.example.nms.support.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #11: provider callbacks through {@link DeliveryCallbacks}, the only writer of callback outcomes
 * (ADR-0003). The HTTP binding is covered in {@code ProviderCallbackIT}.
 */
@SpringBootTest
@Import(DeliveryCallbacksIT.TestBeans.class)
@ExtendWith(OutputCaptureExtension.class)
class DeliveryCallbacksIT {

  private static final String EMAIL = "sim-email";

  @Autowired private DeliveryCallbacks callbacks;
  @Autowired private DeliveryWorker worker;
  @Autowired private DeliveryRepository deliveries;
  @Autowired private DeliveryOutcomes outcomes;
  @Autowired private NotificationRepository notifications;
  @Autowired private MeterRegistry meters;
  @Autowired private SimulatedEmailProvider email;
  @Autowired private AuditRecorder audit;
  @Autowired private TransactionTemplate transactions;
  @Autowired private MutableClock clock;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @ParameterizedTest(name = "{0} -> {1} {2}")
  @CsvSource(
      delimiter = '|',
      nullValues = "null",
      value = {
        "DELIVERED | DELIVERED | null            | null                | DELIVERED",
        "DELIVERED | DELIVERED | null            | 250                 | DELIVERED code 250",
        "BOUNCED   | FAILED    | BOUNCED         | MAILBOX_UNAVAILABLE |"
            + " FAILED BOUNCED, code MAILBOX_UNAVAILABLE",
        "FAILED    | FAILED    | CALLBACK_FAILED | null                | FAILED CALLBACK_FAILED"
      })
  void callbackForASentDeliveryAppliesTheFinalStateAuditsAndCounts(
      CallbackEvent event,
      DeliveryStatus status,
      String reason,
      String reasonCode,
      String audited) {
    Delivery sent = sent(Channel.EMAIL);
    double outcomes = outcomeCount(Channel.EMAIL, status);
    clock.advance(Duration.ofSeconds(5));

    Result result = callbacks.apply(callback(sent, EMAIL, event, reasonCode));

    assertThat(result).isEqualTo(Result.APPLIED);
    Delivery done = reload(sent);
    assertThat(done.getStatus()).isEqualTo(status);
    assertThat(done.getFailureReason()).isEqualTo(reason);
    assertThat(done.getFinalizedAt()).isEqualTo(clock.instant());
    assertThat(done.getSentAt()).as("kept from SENT").isEqualTo(sent.getSentAt());
    assertThat(done.getProviderMessageId())
        .as("kept from SENT")
        .isEqualTo(sent.getProviderMessageId());
    assertThat(done.getVersion()).isEqualTo(sent.getVersion() + 1);
    assertThat(done.getNextAttemptAt()).isNull();
    assertThat(history(sent.getId())).containsExactly("SENT", audited);
    assertThat(outcomeCount(Channel.EMAIL, status)).isEqualTo(outcomes + 1);
  }

  @Test
  void callbackWhileSendingWinsAndTheWorkersLaterSentWriteChangesNothing() {
    Notification n = notifications.save(notification("user-ok", Priority.HIGH, NOW, null));
    Delivery queued = deliveries.save(onProvider(n, CallbackFirstProvider.NAME));
    double sentCount = outcomeCount(Channel.EMAIL, DeliveryStatus.SENT);
    double deliveredCount = outcomeCount(Channel.EMAIL, DeliveryStatus.DELIVERED);

    // The provider's callback arrives inside send(), before the worker writes SENT.
    assertThat(worker.runOnce()).isZero();

    Delivery done = reload(queued);
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(done.getSentAt()).as("coalesce(sent_at, now)").isEqualTo(clock.instant());
    assertThat(done.getFinalizedAt()).isEqualTo(clock.instant());
    assertThat(done.getProviderMessageId()).as("filled by the callback").isEqualTo("cb-msg");
    assertThat(done.getAttemptCount()).isEqualTo(1);
    // Claim (+1) and callback (+1); the worker's SENT write matched no row.
    assertThat(done.getVersion()).isEqualTo(2);
    // History: the callback's DELIVERED only. The SENT write changed nothing, so no SENT event.
    assertThat(history(queued.getId())).containsExactly("DELIVERED");
    assertThat(outcomeCount(Channel.EMAIL, DeliveryStatus.SENT)).isEqualTo(sentCount);
    assertThat(outcomeCount(Channel.EMAIL, DeliveryStatus.DELIVERED)).isEqualTo(deliveredCount + 1);
  }

  @Test
  void callbackForAClaimedDeliveryAppliesAndTheStaleSentWriteIsRejected() {
    Notification n = notifications.save(notification("user-ok", Priority.HIGH, NOW, null));
    Delivery queued = deliveries.save(delivery(n, Channel.EMAIL, NOW));
    assertThat(deliveries.claim(queued.getId(), DeliveryStatus.PENDING, 0, NOW)).isOne();
    Claim claim = Claim.of(queued, n);

    assertThat(callbacks.apply(callback(queued, EMAIL, CallbackEvent.BOUNCED, null)))
        .isEqualTo(Result.APPLIED);
    assertThat(outcomes.sent(claim, "em-late", clock.instant())).isFalse();

    Delivery done = reload(queued);
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(done.getFailureReason()).isEqualTo("BOUNCED");
    assertThat(done.getProviderMessageId()).isEqualTo("em-1");
    assertThat(history(queued.getId())).containsExactly("FAILED BOUNCED");
  }

  @Test
  void callbackRacingACommittingSentWriteIsTimedAfterIt() throws Exception {
    Notification n = notifications.save(notification("user-ok", Priority.HIGH, NOW, null));
    Delivery queued = deliveries.save(delivery(n, Channel.EMAIL, clock.instant()));
    deliveries.claim(queued.getId(), DeliveryStatus.PENDING, 0, clock.instant());
    Instant sentAt = clock.instant().plusSeconds(1);
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      // The worker's SENT write (time sentAt) holds the row lock until it commits.
      Future<?> worker =
          threads.submit(
              () ->
                  transactions.executeWithoutResult(
                      tx -> {
                        assertThat(deliveries.markSent(queued.getId(), 1, "em-1", sentAt)).isOne();
                        audit.sent(
                            n.getSourceSystem(), n.getId(), queued.getId(), Channel.EMAIL, sentAt);
                        written.countDown();
                        await(commit);
                      }));
      await(written);
      Future<Result> callback =
          threads.submit(
              () -> callbacks.apply(callback(queued, EMAIL, CallbackEvent.DELIVERED, null)));
      Thread.sleep(300); // the callback is now waiting for the row lock
      clock.advance(Duration.ofSeconds(1)); // time passes until the worker commits
      commit.countDown();
      worker.get(5, TimeUnit.SECONDS);

      assertThat(callback.get(5, TimeUnit.SECONDS)).isEqualTo(Result.APPLIED);
    } finally {
      threads.shutdownNow();
    }

    Delivery done = reload(queued);
    assertThat(done.getSentAt()).isEqualTo(sentAt);
    assertThat(done.getFinalizedAt()).isAfterOrEqualTo(done.getSentAt());
    assertThat(history(queued.getId())).containsExactly("SENT", "DELIVERED");
  }

  @Test
  void repeatAndLateCallbacksChangeNothingAndAuditCallbackIgnored() {
    Delivery sent = sent(Channel.SMS);
    assertThat(callbacks.apply(callback(sent, "sim-sms", CallbackEvent.DELIVERED, null)))
        .isEqualTo(Result.APPLIED);
    Delivery delivered = reload(sent);
    double failedCount = outcomeCount(Channel.SMS, DeliveryStatus.FAILED);
    double deliveredCount = outcomeCount(Channel.SMS, DeliveryStatus.DELIVERED);
    clock.advance(Duration.ofMinutes(1));

    Result repeat = callbacks.apply(callback(sent, "sim-sms", CallbackEvent.DELIVERED, null));
    Result late = callbacks.apply(callback(sent, "sim-sms", CallbackEvent.FAILED, "30008"));

    assertThat(List.of(repeat, late)).containsOnly(Result.IGNORED);
    assertUnchanged(delivered);
    assertThat(history(sent.getId()))
        .containsExactly(
            "SENT",
            "DELIVERED",
            "CALLBACK_IGNORED already DELIVERED; event DELIVERED",
            "CALLBACK_IGNORED already DELIVERED; event FAILED");
    assertThat(outcomeCount(Channel.SMS, DeliveryStatus.FAILED)).isEqualTo(failedCount);
    assertThat(outcomeCount(Channel.SMS, DeliveryStatus.DELIVERED)).isEqualTo(deliveredCount);
  }

  @Test
  void lateCallbackForADeliveryExpiredAfterALeaseReclaimIsIgnoredAsLateAfterExpiry() {
    Instant start = clock.instant();
    Notification n =
        notifications.save(
            notification("user-ok", Priority.HIGH, start, start.plus(Duration.ofSeconds(10))));
    Delivery queued = deliveries.save(delivery(n, Channel.EMAIL, start));
    // A worker claimed and sent it, then died; the reclaim finds it past expiresAt.
    deliveries.claim(queued.getId(), DeliveryStatus.PENDING, 0, start);
    clock.advance(Duration.ofSeconds(31));
    worker.runOnce();
    Delivery expired = reload(queued);
    assertThat(expired.getStatus()).isEqualTo(DeliveryStatus.EXPIRED);

    Result result = callbacks.apply(callback(queued, EMAIL, CallbackEvent.DELIVERED, null));

    assertThat(result).isEqualTo(Result.IGNORED);
    assertUnchanged(expired);
    assertThat(history(queued.getId()))
        .containsExactly(
            "EXPIRED lease expired after expiresAt",
            "CALLBACK_IGNORED late after expiry; event DELIVERED");
  }

  @Test
  void callbackFromAnotherProviderIsIgnored() {
    Delivery sent = sent(Channel.EMAIL);

    Result result = callbacks.apply(callback(sent, "sim-sms", CallbackEvent.FAILED, null));

    assertThat(result).isEqualTo(Result.IGNORED);
    assertUnchanged(sent);
    assertThat(history(sent.getId()))
        .containsExactly("SENT", "CALLBACK_IGNORED provider mismatch; event FAILED");
  }

  @Test
  void callbackForARetryScheduledDeliveryAppliesAndCancelsTheRetry() {
    Instant start = clock.instant();
    Notification n = notifications.save(notification("user-timeout", Priority.HIGH, start, null));
    Delivery queued = deliveries.save(delivery(n, Channel.EMAIL, start));
    worker.runOnce(); // TIMEOUT: the vendor may have accepted it anyway
    Delivery retrying = reload(queued);
    assertThat(retrying.getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    long sends = email.sendCalls();
    double delivered = outcomeCount(Channel.EMAIL, DeliveryStatus.DELIVERED);

    Result result = callbacks.apply(callback(queued, EMAIL, CallbackEvent.DELIVERED, null));
    clock.advance(Duration.ofHours(1));
    worker.runOnce();

    assertThat(result).isEqualTo(Result.APPLIED);
    Delivery done = reload(queued);
    assertThat(done.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(done.getAttemptCount()).isEqualTo(1);
    assertThat(done.getNextAttemptAt()).isNull();
    assertThat(done.getSentAt()).as("coalesce(sent_at, now)").isEqualTo(done.getFinalizedAt());
    assertThat(email.sendCalls()).as("no further send").isEqualTo(sends);
    // A poll that read the row before the callback cannot claim it: the version moved.
    assertThat(
            deliveries.claim(
                queued.getId(),
                DeliveryStatus.RETRY_SCHEDULED,
                retrying.getVersion(),
                clock.instant()))
        .isZero();
    assertThat(history(queued.getId()))
        .extracting(line -> line.split(" ")[0])
        .containsExactly("ATTEMPT_FAILED", "RETRY_SCHEDULED", "DELIVERED");
    assertThat(outcomeCount(Channel.EMAIL, DeliveryStatus.DELIVERED)).isEqualTo(delivered + 1);
  }

  @Test
  void callbackBeforeAnySendIsIgnored() {
    Notification n = notifications.save(notification("user-ok", Priority.HIGH, NOW, null));
    Delivery pending = deliveries.save(delivery(n, Channel.EMAIL, NOW.plusSeconds(60)));

    Result result = callbacks.apply(callback(pending, EMAIL, CallbackEvent.DELIVERED, null));

    assertThat(result).isEqualTo(Result.IGNORED);
    assertUnchanged(pending);
    assertThat(history(pending.getId()))
        .containsExactly("CALLBACK_IGNORED while PENDING; event DELIVERED");
  }

  @Test
  void unknownDeliveryIsLoggedAndWritesNothing(CapturedOutput output) {
    UUID unknown = UUID.randomUUID();
    double delivered = outcomeCount(Channel.EMAIL, DeliveryStatus.DELIVERED);

    Result result =
        callbacks.apply(
            new ProviderCallback(
                unknown, EMAIL, CallbackEvent.DELIVERED, "em-x", null, clock.instant()));

    assertThat(result).isEqualTo(Result.UNKNOWN);
    assertThat(jdbc.queryForObject("select count(*) from audit_event", Integer.class)).isZero();
    assertThat(outcomeCount(Channel.EMAIL, DeliveryStatus.DELIVERED)).isEqualTo(delivered);
    assertThat(output.getOut()).contains("unknown delivery " + unknown);
  }

  // Helpers

  /** A delivery the worker sent to the simulated provider: {@code SENT}, audited. */
  private Delivery sent(Channel channel) {
    Instant now = clock.instant();
    Notification n = notifications.save(notification("user-ok", Priority.HIGH, now, null));
    Delivery queued = deliveries.save(delivery(n, channel, now));
    worker.runOnce();
    Delivery sent = reload(queued);
    assertThat(sent.getStatus()).isEqualTo(DeliveryStatus.SENT);
    return sent;
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private ProviderCallback callback(
      Delivery delivery, String provider, CallbackEvent event, String reasonCode) {
    return new ProviderCallback(
        delivery.getId(), provider, event, "em-1", reasonCode, clock.instant());
  }

  private void assertUnchanged(Delivery before) {
    Delivery after = reload(before);
    assertThat(after.getStatus()).isEqualTo(before.getStatus());
    assertThat(after.getVersion()).isEqualTo(before.getVersion());
    assertThat(after.getFailureReason()).isEqualTo(before.getFailureReason());
    assertThat(after.getFinalizedAt()).isEqualTo(before.getFinalizedAt());
    assertThat(after.getProviderMessageId()).isEqualTo(before.getProviderMessageId());
  }

  private double outcomeCount(Channel channel, DeliveryStatus status) {
    return meters
        .counter("nms.deliveries.outcome", "channel", channel.name(), "status", status.name())
        .count();
  }

  private Delivery reload(Delivery delivery) {
    return deliveries.findById(delivery.getId()).orElseThrow();
  }

  private List<String> history(UUID deliveryId) {
    return jdbc.query(
        "select type, detail from audit_event where delivery_id = ? order by occurred_at, id",
        (rs, i) ->
            rs.getString("type")
                + (rs.getString("detail") == null ? "" : " " + rs.getString("detail")),
        deliveryId);
  }

  private static Delivery onProvider(Notification notification, String provider) {
    return new Delivery(
        UUID.randomUUID(),
        notification.getId(),
        Channel.EMAIL,
        provider,
        notification.getPriority(),
        RoutingReason.REQUESTED,
        notification.getCreatedAt(),
        notification.getCreatedAt());
  }

  /** A vendor whose DELIVERED callback reaches us before its send call returns. */
  static class CallbackFirstProvider implements ChannelProvider {

    static final String NAME = "callback-first";

    private final DeliveryCallbacks callbacks;
    private final MutableClock clock;

    CallbackFirstProvider(DeliveryCallbacks callbacks, MutableClock clock) {
      this.callbacks = callbacks;
      this.clock = clock;
    }

    @Override
    public String name() {
      return NAME;
    }

    @Override
    public Channel channel() {
      return Channel.EMAIL;
    }

    @Override
    public SendResult send(SendRequest request) {
      Result result =
          callbacks.apply(
              new ProviderCallback(
                  request.deliveryId(),
                  NAME,
                  CallbackEvent.DELIVERED,
                  "cb-msg",
                  null,
                  clock.instant()));
      assertThat(result).isEqualTo(Result.APPLIED);
      return new Accepted("cb-msg");
    }
  }

  @TestConfiguration
  static class TestBeans {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(NOW);
    }

    @Bean
    CallbackFirstProvider callbackFirstProvider(DeliveryCallbacks callbacks, MutableClock clock) {
      return new CallbackFirstProvider(callbacks, clock);
    }
  }
}
