package com.example.nms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #7: Flyway migrations apply on startup and Hibernate schema validation passes against them (the
 * context would not start otherwise).
 */
@SpringBootTest
class SchemaMigrationIT {

  @Autowired private Flyway flyway;

  @Autowired private Environment environment;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void allMigrationsAreAppliedWithoutFailures() {
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().all()).noneMatch(m -> m.getState().isFailed());
    assertThat(Arrays.stream(flyway.info().applied()).map(m -> m.getVersion().getVersion()))
        .contains("1", "2");
  }

  /** #14: V3 adds the dedup columns, the routed flag, and the index that serves the lookup. */
  @Test
  void eventDedupMigrationAddsColumnsAndIndex() {
    assertThat(Arrays.stream(flyway.info().applied()).map(m -> m.getVersion().getVersion()))
        .contains("3");
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.columns"
                    + " where table_name = 'notification'"
                    + " and column_name in ('duplicate_of', 'content_hash') and is_nullable = 'YES'",
                String.class))
        .containsExactlyInAnyOrder("duplicate_of", "content_hash");
    assertThat(
            jdbc.queryForObject(
                "select is_nullable || ' ' || column_default from information_schema.columns"
                    + " where table_name = 'notification' and column_name = 'routed'",
                String.class))
        .isEqualToIgnoringCase("NO FALSE");
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.index_columns"
                    + " where index_name = 'ix_notification_event_dedup' order by ordinal_position",
                String.class))
        .containsExactly(
            "source_system",
            "event_id",
            "type",
            "content_hash",
            "duplicate_of",
            "routed",
            "created_at");
  }

  @Test
  void hibernateValidatesInsteadOfGeneratingSchema() {
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
  }
}
