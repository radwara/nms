package com.example.nms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
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
  void allMigrationsAreApplied() {
    MigrationInfo[] applied = flyway.info().applied();

    assertThat(Arrays.stream(applied).map(m -> m.getVersion().getVersion()))
        .containsExactly("1", "2");
    assertThat(applied).allSatisfy(m -> assertThat(m.getState().isFailed()).isFalse());
    assertThat(flyway.info().pending()).isEmpty();
  }

  @Test
  void hibernateValidatesInsteadOfGeneratingSchema() {
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
  }
}
