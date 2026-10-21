package com.example.nms.intake;

import static com.example.nms.support.ConstraintAssertions.assertViolates;
import static com.example.nms.support.TestEntities.idempotencyRecord;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

/** #7: the database enforces one idempotency record per (source system, idempotency key). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdempotencyRecordConstraintsIT {

  @Autowired private IdempotencyRecordRepository records;

  @Test
  void duplicateSourceSystemAndKeyIsRejected() {
    records.saveAndFlush(idempotencyRecord("billing", "key-1"));

    assertViolates(
        "uk_idempotency_record_source_key",
        () -> records.saveAndFlush(idempotencyRecord("billing", "key-1")));
  }

  @Test
  void sameKeyFromAnotherSourceSystemIsAccepted() {
    records.saveAndFlush(idempotencyRecord("billing", "key-1"));
    records.saveAndFlush(idempotencyRecord("fraud", "key-1"));

    assertThat(records.count()).isEqualTo(2);
  }
}
