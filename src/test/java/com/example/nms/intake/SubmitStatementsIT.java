package com.example.nms.intake;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.audit.AuditEvent;
import com.example.nms.delivery.Delivery;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #8, #9: a 100-recipient submission and its replay load no entities and insert in JDBC batches
 * (batch size 50). Without batching, every insert prepares its own statement (over 800 here).
 * Routing reads the directory with one address query and one preference query.
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
      // Synthetic, seeded-style addresses: both channels usable, so each recipient gets two
      // deliveries by preference.
      jdbc.update(
          "merge into recipient (id, email, phone) key (id) values (?, ?, ?)",
          id,
          "ok.delivered@sim.test",
          "+15550100000");
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
    // Record, notifications, ACCEPTED; then per recipient 2 deliveries, 2 ROUTED, 2 QUEUED.
    assertThat(statistics.getEntityInsertCount()).isEqualTo(1 + 100 + 100 + 200 + 200 + 200);
    assertThat(statistics.getEntityStatistics(Delivery.class.getName()).getInsertCount())
        .isEqualTo(200);
    assertThat(executions("from ChannelPreference")).isEqualTo(1);
    assertThat(executions("new com.example.nms.directory.AddressPresence")).isEqualTo(1);
    assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(25);
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
    assertThat(statistics.getEntityStatistics(Delivery.class.getName()).getInsertCount()).isZero();
    assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(10);
  }

  /** #14: event dedup is one lookup per submission, not one per recipient. */
  @Test
  void dedupIsOneLookupForAllRecipients() {
    intake.submit(IntakeServiceIT.command(RECIPIENTS));
    assertThat(executions("n.contentHash")).isEqualTo(1);
    statistics.clear();

    SubmitResult result = intake.submit(IntakeServiceIT.command(RECIPIENTS)); // new key

    assertThat(((SubmitResult.Created) result).notifications())
        .extracting(SubmittedNotification::outcome)
        .containsOnly(SubmittedNotification.Outcome.SUPPRESSED);
    assertThat(executions("n.contentHash")).isEqualTo(1);
    assertThat(statistics.getEntityLoadCount()).isZero();
    assertThat(statistics.getEntityStatistics(Delivery.class.getName()).getInsertCount()).isZero();
    assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(25);
  }

  /** Executions of the queries whose HQL contains {@code fragment}. */
  private long executions(String fragment) {
    return Arrays.stream(statistics.getQueries())
        .filter(query -> query.contains(fragment))
        .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
        .sum();
  }
}
