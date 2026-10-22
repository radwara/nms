package com.example.nms.intake;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One notification per recipient of a submission. Holds the content; overall status is derived from
 * its deliveries and {@code duplicateOf}, so there is no status column. Ids are assigned by the
 * application, so {@link #isNew()} tells Spring Data to insert without a prior select.
 */
@Entity
public class Notification implements Persistable<UUID> {

  @Id private UUID id;

  private UUID submissionId;

  private String sourceSystem;

  private String eventId;

  private String type;

  @Enumerated(EnumType.STRING)
  private Severity severity;

  @Enumerated(EnumType.STRING)
  private Priority priority;

  private String recipientId;

  /** Channels as requested, in request order; null means "use preferences". */
  @Convert(converter = ChannelListConverter.class)
  private List<Channel> requestedChannels;

  private String subject;

  private String body;

  private Instant scheduledAt;

  private Instant expiresAt;

  private Instant createdAt;

  /** {@link ContentHash} of subject, body, and severity; null only on rows written before V3. */
  @JdbcTypeCode(SqlTypes.CHAR)
  private String contentHash;

  /** The original this notification repeats (suppressed at intake, no deliveries); else null. */
  private UUID duplicateOf;

  @Transient private boolean isNew = true;

  protected Notification() {}

  public Notification(
      UUID id,
      UUID submissionId,
      String sourceSystem,
      String eventId,
      String type,
      Severity severity,
      Priority priority,
      String recipientId,
      List<Channel> requestedChannels,
      String subject,
      String body,
      Instant scheduledAt,
      Instant expiresAt,
      Instant createdAt) {
    this.id = id;
    this.submissionId = submissionId;
    this.sourceSystem = sourceSystem;
    this.eventId = eventId;
    this.type = type;
    this.severity = severity;
    this.priority = priority;
    this.recipientId = recipientId;
    this.requestedChannels = requestedChannels == null ? null : List.copyOf(requestedChannels);
    this.subject = subject;
    this.body = body;
    this.scheduledAt = scheduledAt;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
  }

  /** As the other constructor, plus the dedup fields; {@code duplicateOf} null for an original. */
  public Notification(
      UUID id,
      UUID submissionId,
      String sourceSystem,
      String eventId,
      String type,
      Severity severity,
      Priority priority,
      String recipientId,
      List<Channel> requestedChannels,
      String subject,
      String body,
      Instant scheduledAt,
      Instant expiresAt,
      Instant createdAt,
      String contentHash,
      UUID duplicateOf) {
    this(
        id,
        submissionId,
        sourceSystem,
        eventId,
        type,
        severity,
        priority,
        recipientId,
        requestedChannels,
        subject,
        body,
        scheduledAt,
        expiresAt,
        createdAt);
    this.contentHash = contentHash;
    this.duplicateOf = duplicateOf;
  }

  @Override
  public UUID getId() {
    return id;
  }

  public UUID getSubmissionId() {
    return submissionId;
  }

  public String getSourceSystem() {
    return sourceSystem;
  }

  public String getEventId() {
    return eventId;
  }

  public String getType() {
    return type;
  }

  public Severity getSeverity() {
    return severity;
  }

  public Priority getPriority() {
    return priority;
  }

  public String getRecipientId() {
    return recipientId;
  }

  public List<Channel> getRequestedChannels() {
    return requestedChannels;
  }

  public String getSubject() {
    return subject;
  }

  public String getBody() {
    return body;
  }

  public Instant getScheduledAt() {
    return scheduledAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getContentHash() {
    return contentHash;
  }

  public UUID getDuplicateOf() {
    return duplicateOf;
  }

  /** Suppressed at intake as a repeat of {@link #getDuplicateOf()}: it has no deliveries. */
  public boolean isSuppressed() {
    return duplicateOf != null;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNew = false;
  }
}
