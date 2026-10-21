package com.example.nms.audit;

import static com.example.nms.support.TestEntities.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

/** #7: audit ids come from the pooled sequence and the type is stored by name. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditEventMappingIT {

  @Autowired private AuditEventRepository events;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void batchOfEventsGetsDistinctSequenceIdsAndNamedTypes() {
    UUID notificationId = UUID.randomUUID();
    List<AuditEvent> saved =
        events.saveAllAndFlush(
            List.of(
                event(notificationId, AuditEventType.ACCEPTED),
                event(notificationId, AuditEventType.ROUTED),
                event(notificationId, AuditEventType.QUEUED)));

    assertThat(saved).extracting(AuditEvent::getId).doesNotContainNull().doesNotHaveDuplicates();
    assertThat(
            jdbc.queryForList(
                "select type from audit_event where notification_id = ? order by id",
                String.class,
                notificationId))
        .containsExactly("ACCEPTED", "ROUTED", "QUEUED");
  }

  private static AuditEvent event(UUID notificationId, AuditEventType type) {
    return new AuditEvent("billing", notificationId, null, type, Channel.EMAIL, null, NOW);
  }
}
