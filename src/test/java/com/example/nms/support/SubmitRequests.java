package com.example.nms.support;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Submit request bodies for tests. Synthetic data; recipients are seeded by V2. */
public final class SubmitRequests {

  public static final String PATH = "/api/v1/notifications";
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private SubmitRequests() {}

  /** A valid body for {@code userIds}, no channels requested, expiring in one day. */
  public static ObjectNode valid(String... userIds) {
    ObjectNode body = JsonMapper.shared().createObjectNode();
    body.put("eventId", "evt-1");
    body.put("type", "FRAUD_ALERT");
    body.put("severity", "CRITICAL");
    body.put("priority", "HIGH");
    ArrayNode recipients = body.putArray("recipients");
    for (String userId : userIds) {
      recipients.addObject().put("userId", userId);
    }
    body.put("subject", "Suspicious login");
    body.put("body", "We noticed a login from a new device.");
    body.putNull("scheduledAt");
    body.put("expiresAt", Instant.now().plus(Duration.ofDays(1)).toString());
    return body;
  }

  public static String newKey() {
    return "key-" + UUID.randomUUID();
  }

  /** Removes everything a submission writes; the seeded directory stays. */
  public static void clearSubmissions(JdbcTemplate jdbc) {
    jdbc.update("delete from delivery");
    jdbc.update("delete from audit_event");
    jdbc.update("delete from notification");
    jdbc.update("delete from idempotency_record");
  }
}
