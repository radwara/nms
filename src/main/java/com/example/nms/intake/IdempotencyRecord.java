package com.example.nms.intake;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One accepted submission per (source system, idempotency key). */
@Entity
public class IdempotencyRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String sourceSystem;

  private String idempotencyKey;

  /** Fixed-length hex SHA-256; the column is char(64). */
  @JdbcTypeCode(SqlTypes.CHAR)
  private String requestHash;

  private UUID submissionId;

  private Instant createdAt;

  private Instant expiresAt;

  protected IdempotencyRecord() {}

  public IdempotencyRecord(
      String sourceSystem,
      String idempotencyKey,
      String requestHash,
      UUID submissionId,
      Instant createdAt,
      Instant expiresAt) {
    this.sourceSystem = sourceSystem;
    this.idempotencyKey = idempotencyKey;
    this.requestHash = requestHash;
    this.submissionId = submissionId;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public Long getId() {
    return id;
  }

  public String getSourceSystem() {
    return sourceSystem;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public UUID getSubmissionId() {
    return submissionId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
