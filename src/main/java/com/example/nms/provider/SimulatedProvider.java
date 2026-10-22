package com.example.nms.provider;

import com.example.nms.common.Channel;
import com.example.nms.provider.SendResult.Accepted;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A simulated vendor: answers synchronously from the address convention, with no network or
 * sleeping ({@code TIMEOUT} is returned as a result). Dedupes on {@code deliveryId} like a real
 * vendor honouring an idempotency key: a resend after acceptance returns the same message id and is
 * not a new message. State is in memory and lost on restart, which a real vendor's is not.
 */
public abstract class SimulatedProvider implements ChannelProvider {

  /** {@code Retry-After} sent with every simulated {@code RATE_LIMITED}. */
  public static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  /** Failures per delivery before a {@code flaky2} address is accepted. */
  public static final int FLAKY_FAILURES = 2;

  private final String name;
  private final Channel channel;
  private final String messageIdPrefix;
  private final Map<UUID, String> accepted = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> flakyFailures = new ConcurrentHashMap<>();
  private final AtomicLong sendCalls = new AtomicLong();

  SimulatedProvider(String name, Channel channel, String messageIdPrefix) {
    this.name = name;
    this.channel = channel;
    this.messageIdPrefix = messageIdPrefix;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Channel channel() {
    return channel;
  }

  @Override
  public SendResult send(SendRequest request) {
    sendCalls.incrementAndGet();
    String messageId = accepted.get(request.deliveryId());
    if (messageId != null) {
      return new Accepted(messageId);
    }
    Optional<SimulatedSend> outcome = outcomeFor(request.address());
    if (outcome.isEmpty()) {
      return SimulatedSend.UNKNOWN_ADDRESS;
    }
    SimulatedSend send = outcome.get();
    if (send == SimulatedSend.FLAKY2
        && flakyFailures.merge(request.deliveryId(), 1, Integer::sum) > FLAKY_FAILURES) {
      return accept(request.deliveryId());
    }
    return send == SimulatedSend.OK ? accept(request.deliveryId()) : send.failure();
  }

  /** Distinct messages accepted; a deduped resend does not count. */
  public int acceptedMessages() {
    return accepted.size();
  }

  /** Every send call, including failures and deduped resends. */
  public long sendCalls() {
    return sendCalls.get();
  }

  /** The send outcome encoded in {@code address}; empty if it does not follow the convention. */
  abstract Optional<SimulatedSend> outcomeFor(String address);

  private Accepted accept(UUID deliveryId) {
    return new Accepted(
        accepted.computeIfAbsent(deliveryId, id -> messageIdPrefix + UUID.randomUUID()));
  }
}
