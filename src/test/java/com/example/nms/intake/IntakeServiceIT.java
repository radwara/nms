package com.example.nms.intake;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.audit.AuditEvent;
import com.example.nms.audit.AuditEventRepository;
import com.example.nms.audit.AuditEventType;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** #8: intake transaction details that the API tests cannot see. */
@SpringBootTest
@Import(IntakeServiceIT.AuditingRoutingConfiguration.class)
class IntakeServiceIT {

  @Autowired private IntakeService intake;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
  }

  @Test
  void acceptedIsAuditedBeforeRouting() {
    SubmitResult result = intake.submit(command("user-ok", "user-email-only"));

    assertThat(result).isInstanceOf(SubmitResult.Created.class);
    for (SubmittedNotification n : ((SubmitResult.Created) result).notifications()) {
      assertThat(
              jdbc.queryForList(
                  "select type from audit_event where notification_id = ? order by occurred_at, id",
                  String.class,
                  n.notificationId()))
          .containsExactly("ACCEPTED", "ROUTED");
    }
  }

  static SubmitCommand command(String... userIds) {
    return new SubmitCommand(
        "demo-app",
        "key-" + UUID.randomUUID(),
        "evt-1",
        "FRAUD_ALERT",
        Severity.INFO,
        Priority.NORMAL,
        Arrays.stream(userIds).map(id -> new SubmitCommand.Recipient(id, null)).toList(),
        "subject",
        "body",
        null,
        null);
  }

  /** Stands in for routing (#9): one ROUTED event per notification, in the same transaction. */
  @TestConfiguration(proxyBeanMethods = false)
  static class AuditingRoutingConfiguration {

    @Bean
    @Primary
    RoutingStep auditingRoutingStep(AuditEventRepository events) {
      return (List<Notification> notifications, Instant now) ->
          notifications.forEach(
              n ->
                  events.save(
                      new AuditEvent(
                          n.getSourceSystem(),
                          n.getId(),
                          null,
                          AuditEventType.ROUTED,
                          null,
                          "stub",
                          now)));
    }
  }
}
