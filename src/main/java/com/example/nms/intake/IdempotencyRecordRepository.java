package com.example.nms.intake;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

  Optional<IdempotencyRecord> findBySourceSystemAndIdempotencyKey(
      String sourceSystem, String idempotencyKey);

  /**
   * Takes over an expired record for a new submission. One conditional update, never delete and
   * insert: 1 row updated means this request owns the key; 0 means a concurrent request renewed it
   * first.
   */
  @Modifying
  @Query(
      """
      update IdempotencyRecord r
         set r.requestHash = :requestHash, r.submissionId = :submissionId,
             r.createdAt = :now, r.expiresAt = :expiresAt
       where r.sourceSystem = :sourceSystem and r.idempotencyKey = :idempotencyKey
         and r.expiresAt <= :now
      """)
  int replaceExpired(
      @Param("sourceSystem") String sourceSystem,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("requestHash") String requestHash,
      @Param("submissionId") UUID submissionId,
      @Param("now") Instant now,
      @Param("expiresAt") Instant expiresAt);
}
