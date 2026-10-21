package com.example.nms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * #7: Flyway migrations apply on startup and Hibernate schema validation passes against them (the
 * context would not start otherwise).
 */
@SpringBootTest
class SchemaMigrationIT {

  @Autowired private Flyway flyway;

  @Autowired private Environment environment;

  @Test
  void allMigrationsAreAppliedWithoutFailures() {
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().all()).noneMatch(m -> m.getState().isFailed());
    assertThat(Arrays.stream(flyway.info().applied()).map(m -> m.getVersion().getVersion()))
        .contains("1", "2");
  }

  @Test
  void hibernateValidatesInsteadOfGeneratingSchema() {
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
  }
}
