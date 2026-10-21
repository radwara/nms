package com.example.nms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** #8: transaction boundaries and detail of audit writes. */
@SpringBootTest
class AuditRecorderIT {

  @Autowired private AuditRecorder recorder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void clean() {
    jdbc.update("delete from audit_event");
  }

  @Test
  void rejectedCommitsInItsOwnTransactionWhenTheCallerRollsBack() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              recorder.accepted("demo-app", UUID.randomUUID(), Instant.now());
              recorder.rejected("demo-app", List.of(4223));
              status.setRollbackOnly();
            });

    assertThat(jdbc.queryForList("select type from audit_event", String.class))
        .containsExactly("REJECTED");
  }

  @Test
  void acceptedAndDuplicateJoinTheCallersTransactionAndNeedOne() {
    assertThatThrownBy(() -> recorder.accepted("demo-app", UUID.randomUUID(), Instant.now()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(
            () -> recorder.duplicateSubmission("demo-app", UUID.randomUUID(), Instant.now()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void rejectedDetailListsDistinctCodesWithinTheColumnLimit() {
    recorder.rejected("demo-app", List.of(4004, 4003, 4004));
    recorder.rejected("demo-app", IntStream.range(1000, 1200).boxed().toList());

    List<String> details =
        jdbc.queryForList("select detail from audit_event order by id", String.class);
    assertThat(details.get(0)).isEqualTo("codes 4004,4003");
    assertThat(details.get(1))
        .hasSize(AuditRecorder.MAX_DETAIL_LENGTH)
        .startsWith("codes 1000,1001");
  }
}
