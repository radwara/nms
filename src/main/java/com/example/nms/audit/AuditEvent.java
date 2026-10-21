package com.example.nms.audit;

import com.example.nms.common.Channel;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Append-only audit record. {@code detail} holds codes and reasons only, never content or contact
 * details.
 */
@Entity
@Immutable
public class AuditEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String sourceSystem;

  /** Null for rejected requests. */
  private UUID notificationId;

  private UUID deliveryId;

  @Enumerated(EnumType.STRING)
  private AuditEventType type;

  @Enumerated(EnumType.STRING)
  private Channel channel;

  private String detail;

  private Instant occurredAt;

  protected AuditEvent() {}

  public AuditEvent(
      String sourceSystem,
      UUID notificationId,
      UUID deliveryId,
      AuditEventType type,
      Channel channel,
      String detail,
      Instant occurredAt) {
    this.sourceSystem = sourceSystem;
    this.notificationId = notificationId;
    this.deliveryId = deliveryId;
    this.type = type;
    this.channel = channel;
    this.detail = detail;
    this.occurredAt = occurredAt;
  }

  public Long getId() {
    return id;
  }

  public String getSourceSystem() {
    return sourceSystem;
  }

  public UUID getNotificationId() {
    return notificationId;
  }

  public UUID getDeliveryId() {
    return deliveryId;
  }

  public AuditEventType getType() {
    return type;
  }

  public Channel getChannel() {
    return channel;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
