package com.example.nms.intake;

import static com.example.nms.support.TestApiKeys.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import com.example.nms.common.Priority;
import com.example.nms.common.Severity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #8: canonical hash over the validated fields. */
class RequestHashTest {

  private static final Instant EXPIRES = Instant.parse("2026-10-22T10:00:00Z");

  @Test
  void isLowercaseHexSha256OfTheCanonicalForm() {
    String canonical =
        "{\"body\":\"b\",\"eventId\":\"e\",\"expiresAt\":\"2026-10-22T10:00:00Z\","
            + "\"priority\":\"HIGH\",\"recipients\":[{\"channels\":[\"SMS\",\"EMAIL\"],"
            + "\"userId\":\"a\"},{\"channels\":null,\"userId\":\"b\"}],\"scheduledAt\":null,"
            + "\"severity\":\"CRITICAL\",\"subject\":\"s\",\"type\":\"t\"}";

    assertThat(RequestHash.of(command(recipients(), null, EXPIRES)))
        .hasSize(64)
        .isEqualTo(sha256Hex(canonical));
  }

  @Test
  void sourceSystemAndKeyAreNotPartOfThePayload() {
    SubmitCommand base = command(recipients(), null, EXPIRES);
    SubmitCommand other =
        new SubmitCommand(
            "other-app",
            "other-key",
            base.eventId(),
            base.type(),
            base.severity(),
            base.priority(),
            base.recipients(),
            base.subject(),
            base.body(),
            base.scheduledAt(),
            base.expiresAt());

    assertThat(RequestHash.of(other)).isEqualTo(RequestHash.of(base));
  }

  @Test
  void recipientAndChannelOrderMatter() {
    String hash = RequestHash.of(command(recipients(), null, EXPIRES));
    List<SubmitCommand.Recipient> swappedRecipients =
        List.of(recipients().get(1), recipients().get(0));
    List<SubmitCommand.Recipient> swappedChannels =
        List.of(
            new SubmitCommand.Recipient("a", List.of(Channel.EMAIL, Channel.SMS)),
            recipients().get(1));

    assertThat(RequestHash.of(command(swappedRecipients, null, EXPIRES))).isNotEqualTo(hash);
    assertThat(RequestHash.of(command(swappedChannels, null, EXPIRES))).isNotEqualTo(hash);
  }

  @Test
  void optionalValuesChangeTheHash() {
    String hash = RequestHash.of(command(recipients(), null, EXPIRES));

    assertThat(RequestHash.of(command(recipients(), EXPIRES, EXPIRES))).isNotEqualTo(hash);
    assertThat(RequestHash.of(command(recipients(), null, null))).isNotEqualTo(hash);
  }

  private static List<SubmitCommand.Recipient> recipients() {
    return List.of(
        new SubmitCommand.Recipient("a", List.of(Channel.SMS, Channel.EMAIL)),
        new SubmitCommand.Recipient("b", null));
  }

  private static SubmitCommand command(
      List<SubmitCommand.Recipient> recipients, Instant scheduledAt, Instant expiresAt) {
    return new SubmitCommand(
        "demo-app",
        "key",
        "e",
        "t",
        Severity.CRITICAL,
        Priority.HIGH,
        recipients,
        "s",
        "b",
        scheduledAt,
        expiresAt);
  }
}
