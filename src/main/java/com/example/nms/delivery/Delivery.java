package com.example.nms.delivery;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One delivery per (notification, channel); the worker's unit of work and queue row (ADR-0004). The
 * id doubles as the provider idempotency key.
 */
@Entity
public class Delivery {

  @Id private UUID id;

  private UUID notificationId;

  @Enumerated(EnumType.STRING)
  private Channel channel;

  private String provider;

  @Enumerated(EnumType.STRING)
  private DeliveryStatus status;

  /** Stored as the numeric code so the poll orders by priority without a join. */
  @Convert(converter = PriorityCodeConverter.class)
  private Priority priority;

  @Enumerated(EnumType.STRING)
  private RoutingReason routingReason;

  private int attemptCount;

  private Instant nextAttemptAt;

  private Instant claimedAt;

  private String providerMessageId;

  private String failureReason;

  private Instant sentAt;

  private Instant finalizedAt;

  private Instant createdAt;

  private Instant updatedAt;

  @Version private Long version;

  protected Delivery() {}

  /** A new {@code PENDING} delivery, due at {@code nextAttemptAt}. Every argument is required. */
  public Delivery(
      UUID id,
      UUID notificationId,
      Channel channel,
      String provider,
      Priority priority,
      RoutingReason routingReason,
      Instant nextAttemptAt,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.notificationId = Objects.requireNonNull(notificationId, "notificationId");
    this.channel = Objects.requireNonNull(channel, "channel");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.status = DeliveryStatus.PENDING;
    this.priority = Objects.requireNonNull(priority, "priority");
    this.routingReason = Objects.requireNonNull(routingReason, "routingReason");
    this.attemptCount = 0;
    this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getNotificationId() {
    return notificationId;
  }

  public Channel getChannel() {
    return channel;
  }

  public String getProvider() {
    return provider;
  }

  public DeliveryStatus getStatus() {
    return status;
  }

  public Priority getPriority() {
    return priority;
  }

  public RoutingReason getRoutingReason() {
    return routingReason;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant getClaimedAt() {
    return claimedAt;
  }

  public String getProviderMessageId() {
    return providerMessageId;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public Instant getFinalizedAt() {
    return finalizedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Long getVersion() {
    return version;
  }
}
