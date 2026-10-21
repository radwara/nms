package com.example.nms.intake;

import static com.example.nms.support.SubmitRequests.clearSubmissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** #8: intake transaction details that the API tests cannot see. */
@SpringBootTest
@Import(IntakeServiceIT.AuditingRoutingConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class IntakeServiceIT {

  @Autowired private IntakeService intake;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private AuditingRoutingStep routing;

  @BeforeEach
  void clean() {
    clearSubmissions(jdbc);
    routing.fail = false;
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

  @Test
  void acceptedIsLoggedOnlyAfterCommit(CapturedOutput output) {
    intake.submit(command("user-ok"));
    assertThat(output.getOut()).contains("accepted: 1 notifications");

    routing.fail = true;
    assertThatThrownBy(() -> intake.submit(command("user-email-only")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(output.getOut().split("accepted: 1 notifications", -1)).hasSize(2);
    assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void refusesToJoinACallersTransaction() {
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> intake.submit(command("user-ok"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not run inside a transaction");
    assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).isZero();
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

  /**
   * Stands in for routing (#9): one ROUTED event per notification, in the same transaction; can be
   * told to make the commit fail.
   */
  static class AuditingRoutingStep implements RoutingStep {

    private final AuditEventRepository events;
    volatile boolean fail;

    AuditingRoutingStep(AuditEventRepository events) {
      this.events = events;
    }

    @Override
    public void route(List<Notification> notifications, Instant now) {
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
      if (fail) {
        // Fails the commit itself, after all intake code has run.
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException("commit failed");
              }
            });
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class AuditingRoutingConfiguration {

    @Bean
    @Primary
    AuditingRoutingStep auditingRoutingStep(AuditEventRepository events) {
      return new AuditingRoutingStep(events);
    }
  }
}
