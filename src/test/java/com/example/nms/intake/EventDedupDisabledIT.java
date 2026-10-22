package com.example.nms.intake;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.intake.SubmittedNotification.Outcome;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #14, ADR-0006: {@code nms.dedup.enabled=false} restores greenfield behaviour without a lookup,
 * and still writes the content hash so re-enabling works at once.
 */
@SpringBootTest(
    properties = {
      "nms.dedup.enabled=false",
      "spring.jpa.properties.hibernate.generate_statistics=true"
    })
class EventDedupDisabledIT {

  @Autowired private IntakeService intake;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void repeatIsDeliveredAndNoDedupQueryRuns() {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    SubmitResult first = intake.submit(IntakeServiceIT.command("user-ok"));
    SubmitResult repeat = intake.submit(IntakeServiceIT.command("user-ok"));

    for (SubmitResult result : new SubmitResult[] {first, repeat}) {
      assertThat(((SubmitResult.Created) result).notifications())
          .extracting(SubmittedNotification::outcome)
          .containsExactly(Outcome.ACCEPTED);
    }
    assertThat(jdbc.queryForObject("select count(*) from delivery", Integer.class)).isEqualTo(4);
    assertThat(
            jdbc.queryForList(
                "select content_hash from notification where duplicate_of is null", String.class))
        .hasSize(2)
        .allMatch(hash -> hash != null && hash.length() == 64);
    assertThat(
            Arrays.stream(statistics.getQueries())
                .filter(query -> query.contains("n.contentHash"))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
                .sum())
        .isZero();
  }
}
