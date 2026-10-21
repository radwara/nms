package com.example.nms.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

/** #7: the database enforces one idempotency record per (source system, idempotency key). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdempotencyRecordConstraintsIT {

  private static final String HASH = "a".repeat(64);

  @Autowired private IdempotencyRecordRepository records;

  @Test
  void duplicateSourceSystemAndKeyIsRejected() {
    records.saveAndFlush(record("billing", "key-1"));

    assertThatThrownBy(() -> records.saveAndFlush(record("billing", "key-1")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void sameKeyFromAnotherSourceSystemIsAccepted() {
    records.saveAndFlush(record("billing", "key-1"));
    records.saveAndFlush(record("fraud", "key-1"));

    assertThat(records.count()).isEqualTo(2);
  }

  private static IdempotencyRecord record(String sourceSystem, String key) {
    Instant now = Instant.now();
    return new IdempotencyRecord(
        sourceSystem, key, HASH, UUID.randomUUID(), now, now.plus(Duration.ofDays(7)));
  }
}
