package com.example.nms.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.intake.SubmittedNotification;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** #14, ADR-0006: per-item outcome; an accepted item keeps the greenfield shape byte for byte. */
class SubmitResponseTest {

  private static final UUID ID = UUID.fromString("6f1c0000-0000-4000-8000-000000000001");
  private static final UUID ORIGINAL = UUID.fromString("9b2e0000-0000-4000-8000-000000000002");

  @Test
  void acceptedItemHasNoDuplicateOfKey() {
    String json =
        JsonMapper.shared()
            .writeValueAsString(SubmitResponse.of(List.of(new SubmittedNotification("u1", ID))));

    assertThat(json)
        .isEqualTo(
            "{\"notifications\":[{\"recipientId\":\"u1\",\"notificationId\":\""
                + ID
                + "\",\"status\":\"ACCEPTED\"}]}");
  }

  @Test
  void suppressedItemCarriesDuplicateOf() {
    String json =
        JsonMapper.shared()
            .writeValueAsString(
                SubmitResponse.of(List.of(new SubmittedNotification("u1", ID, ORIGINAL))));

    assertThat(json)
        .isEqualTo(
            "{\"notifications\":[{\"recipientId\":\"u1\",\"notificationId\":\""
                + ID
                + "\",\"status\":\"SUPPRESSED\",\"duplicateOf\":\""
                + ORIGINAL
                + "\"}]}");
  }
}
