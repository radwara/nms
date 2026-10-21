package com.example.nms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import com.example.nms.intake.RequestHash;
import com.example.nms.intake.SubmitCommand;
import com.example.nms.security.SourceSystem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** #8: submit validation rules not worth a full context each. */
class SubmitRequestValidatorTest {

  private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
  private static final SourceSystem SOURCE = new SourceSystem("demo-app");

  private final SubmitRequestValidator validator =
      new SubmitRequestValidator(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void validRequestBecomesACommandWithTheSourceSystemFromTheKey() {
    ObjectNode body = valid();
    recipient(body, 0).putArray("channels").add("SMS").add("EMAIL");
    body.put("scheduledAt", "2026-10-21T12:00:00.123456789+02:00");
    body.put("sourceSystem", "spoofed");

    SubmitCommand command = validator.validate(SOURCE, "key-1", body);

    assertThat(command.sourceSystem()).isEqualTo("demo-app");
    assertThat(command.idempotencyKey()).isEqualTo("key-1");
    assertThat(command.requestHash()).isEqualTo(RequestHash.of(body));
    assertThat(command.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(command.priority()).isEqualTo(Priority.HIGH);
    assertThat(command.recipients())
        .containsExactly(
            new SubmitCommand.Recipient("user-ok", List.of(Channel.SMS, Channel.EMAIL)),
            new SubmitCommand.Recipient("user-email-only", null));
    assertThat(command.scheduledAt()).isEqualTo(Instant.parse("2026-10-21T10:00:00.123456Z"));
    assertThat(command.expiresAt()).isEqualTo(Instant.parse("2026-10-22T10:00:00Z"));
  }

  @Test
  void zoneIdsAreAcceptedAsOffsets() {
    ObjectNode body = valid();
    body.put("expiresAt", "2026-10-22T10:00:00+02:00[Europe/Paris]");

    assertThat(validator.validate(SOURCE, "k", body).expiresAt())
        .isEqualTo(Instant.parse("2026-10-22T08:00:00Z"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"2026-10-22T10:00:00", "2026-10-22", "1761040800", "+10000-01-01T00:00:00Z"})
  void timestampsWithoutOffsetOrOutOfRangeAre4003(String value) {
    ObjectNode body = valid();
    body.put("expiresAt", value);

    assertThat(errors(body)).containsExactly("4003 expiresAt");
  }

  @Test
  void wrongJsonTypesAre4003() {
    ObjectNode body = valid();
    body.put("eventId", 42);
    body.put("severity", 1);
    body.putArray("subject");
    recipient(body, 1).put("userId", true);

    assertThat(errors(body))
        .containsExactly(
            "4003 eventId", "4003 severity", "4003 recipients[1].userId", "4003 subject");
  }

  @Test
  void blankAndNullRequiredFieldsAre4004() {
    ObjectNode body = valid();
    body.put("eventId", "  ");
    body.putNull("type");
    recipient(body, 0).put("userId", "");

    assertThat(errors(body))
        .containsExactly("4004 eventId", "4004 type", "4004 recipients[0].userId");
  }

  @Test
  void blankIdempotencyKeyIs4001() {
    assertThat(errors(" ", valid())).containsExactly("4001 Idempotency-Key");
  }

  @Test
  void enumsAreCaseSensitive() {
    ObjectNode body = valid();
    body.put("priority", "high");

    assertThat(errors(body)).containsExactly("4003 priority");
  }

  @Test
  void recipientsMustBeANonEmptyArrayOfObjects() {
    ObjectNode body = valid();
    body.put("recipients", "user-ok");
    assertThat(errors(body)).containsExactly("4003 recipients");

    body.putArray("recipients");
    assertThat(errors(body)).containsExactly("4004 recipients");

    body.putArray("recipients").add("user-ok");
    assertThat(errors(body)).containsExactly("4003 recipients[0]");
  }

  @Test
  void exactly100RecipientsAreAllowed() {
    ObjectNode body = valid();
    ArrayNode recipients = body.putArray("recipients");
    for (int i = 0; i < 100; i++) {
      recipients.addObject().put("userId", "user-" + i);
    }

    assertThat(validator.validate(SOURCE, "k", body).recipients()).hasSize(100);
  }

  @Test
  void userIdOver64CharactersIs4003() {
    ObjectNode body = valid();
    recipient(body, 0).put("userId", "u".repeat(65));

    assertThat(errors(body)).containsExactly("4003 recipients[0].userId");
  }

  @Test
  void channelsMustBeANonEmptyListOfDistinctKnownChannels() {
    ObjectNode body = valid();
    recipient(body, 0).put("channels", "SMS");
    recipient(body, 1).putArray("channels");
    assertThat(errors(body))
        .containsExactly("4003 recipients[0].channels", "4003 recipients[1].channels");

    recipient(body, 0).putArray("channels").add("SMS").add("PUSH").add("SMS");
    recipient(body, 1).putNull("channels");
    assertThat(errors(body))
        .containsExactly("4003 recipients[0].channels[1]", "4003 recipients[0].channels[2]");
  }

  @Test
  void expiresAtEqualToNowIsInThePast() {
    ObjectNode body = valid();
    body.put("expiresAt", NOW.toString());

    assertThat(errors(body)).containsExactly("4221 expiresAt");
  }

  @Test
  void scheduledAtEqualToExpiresAtIsAccepted() {
    ObjectNode body = valid();
    body.put("scheduledAt", body.get("expiresAt").stringValue());

    assertThat(validator.validate(SOURCE, "k", body).scheduledAt()).isNotNull();
  }

  @Test
  void bothSemanticErrorsAreReportedTogether() {
    ObjectNode body = valid();
    body.put("expiresAt", "2026-10-20T00:00:00Z");
    body.put("scheduledAt", "2026-10-20T00:00:01Z");

    assertThat(errors(body)).containsExactly("4221 expiresAt", "4222 scheduledAt");
  }

  @Test
  void nonObjectBodyIs4003AlongsideAMissingKey() {
    assertThat(errors(null, JsonMapper.shared().createArrayNode()))
        .containsExactly("4001 Idempotency-Key", "4003 null");
  }

  private List<String> errors(JsonNode body) {
    return errors("k", body);
  }

  private List<String> errors(String key, JsonNode body) {
    ApiException ex =
        catchThrowableOfType(ApiException.class, () -> validator.validate(SOURCE, key, body));
    assertThat(ex).as("validation fails").isNotNull();
    return ex.errors().stream().map(e -> e.code().code() + " " + e.field()).toList();
  }

  private static ObjectNode recipient(ObjectNode body, int index) {
    return (ObjectNode) body.get("recipients").get(index);
  }

  private static ObjectNode valid() {
    ObjectNode body = JsonMapper.shared().createObjectNode();
    body.put("eventId", "evt-1");
    body.put("type", "FRAUD_ALERT");
    body.put("severity", "CRITICAL");
    body.put("priority", "HIGH");
    ArrayNode recipients = body.putArray("recipients");
    recipients.addObject().put("userId", "user-ok");
    recipients.addObject().put("userId", "user-email-only");
    body.put("subject", "Suspicious login");
    body.put("body", "We noticed a login from a new device.");
    body.put("expiresAt", "2026-10-22T10:00:00Z");
    return body;
  }
}
