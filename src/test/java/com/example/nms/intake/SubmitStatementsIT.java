package com.example.nms.intake;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.audit.AuditEvent;
import jakarta.persistence.EntityManagerFactory;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #8: a 100-recipient submission and its replay load no entities and insert in JDBC batches (batch
 * size 50). Without batching, every insert prepares its own statement (over 200 here).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class SubmitStatementsIT {

  private static final String[] RECIPIENTS =
      IntStream.range(0, 100).mapToObj(i -> "stmt-user-" + i).toArray(String[]::new);

  @Autowired private IntakeService intake;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private Statistics statistics;

  @BeforeEach
  void setUp() {
    clearSubmissions(jdbc);
    for (String id : RECIPIENTS) {
      jdbc.update("merge into recipient (id) key (id) values (?)", id);
    }
    statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
  }

  @Test
  void createLoadsNoEntitiesAndBatchesInserts() {
    SubmitResult result = intake.submit(IntakeServiceIT.command(RECIPIENTS));

    assertThat(result).isInstanceOf(SubmitResult.Created.class);
    assertThat(statistics.getEntityLoadCount()).isZero();
    assertThat(statistics.getEntityFetchCount()).isZero();
    assertThat(statistics.getEntityInsertCount()).isEqualTo(1 + 100 + 100);
    assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(15);
  }

  @Test
  void replayLoadsNoNotificationsAndBatchesItsAuditInserts() {
    SubmitCommand command = IntakeServiceIT.command(RECIPIENTS);
    intake.submit(command);
    statistics.clear();

    SubmitResult result = intake.submit(command);

    assertThat(result).isInstanceOf(SubmitResult.Replayed.class);
    assertThat(statistics.getEntityStatistics(Notification.class.getName()).getLoadCount())
        .isZero();
    assertThat(statistics.getEntityStatistics(AuditEvent.class.getName()).getInsertCount())
        .isEqualTo(100);
    assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(10);
  }
}
