package com.example.nms.intake;

import static com.example.nms.support.TestApiKeys.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** #8: canonical request hash. */
class RequestHashTest {

  private static final String REQUEST =
      "{\"eventId\":\"e\",\"recipients\":[{\"userId\":\"a\",\"channels\":[\"SMS\",\"EMAIL\"]},"
          + "{\"userId\":\"b\"}],\"scheduledAt\":null}";

  @Test
  void isLowercaseHexSha256OfTheSortedKeyForm() {
    String canonical =
        "{\"eventId\":\"e\",\"recipients\":[{\"channels\":[\"SMS\",\"EMAIL\"],\"userId\":\"a\"},"
            + "{\"userId\":\"b\"}],\"scheduledAt\":null}";

    assertThat(RequestHash.of(parse(REQUEST))).hasSize(64).isEqualTo(sha256Hex(canonical));
  }

  @Test
  void ignoresWhitespaceAndKeyOrderAtEveryLevel() {
    String reordered =
        "{\n  \"scheduledAt\" : null,\n  \"recipients\" : [ { \"channels\" : [ \"SMS\", \"EMAIL\" ],"
            + " \"userId\" : \"a\" }, { \"userId\" : \"b\" } ],\n  \"eventId\" : \"e\"\n}";

    assertThat(RequestHash.of(parse(reordered))).isEqualTo(RequestHash.of(parse(REQUEST)));
  }

  @Test
  void arrayOrderMatters() {
    String recipientsSwapped =
        "{\"eventId\":\"e\",\"recipients\":[{\"userId\":\"b\"},"
            + "{\"userId\":\"a\",\"channels\":[\"SMS\",\"EMAIL\"]}],\"scheduledAt\":null}";
    String channelsSwapped = REQUEST.replace("[\"SMS\",\"EMAIL\"]", "[\"EMAIL\",\"SMS\"]");

    assertThat(RequestHash.of(parse(recipientsSwapped)))
        .isNotEqualTo(RequestHash.of(parse(REQUEST)));
    assertThat(RequestHash.of(parse(channelsSwapped))).isNotEqualTo(RequestHash.of(parse(REQUEST)));
  }

  @Test
  void anyValueChangeMatters() {
    assertThat(RequestHash.of(parse(REQUEST.replace("\"e\"", "\"f\""))))
        .isNotEqualTo(RequestHash.of(parse(REQUEST)));
    assertThat(RequestHash.of(parse(REQUEST.replace(",\"scheduledAt\":null", ""))))
        .isNotEqualTo(RequestHash.of(parse(REQUEST)));
  }

  private static JsonNode parse(String json) {
    return JsonMapper.shared().readTree(json);
  }
}
