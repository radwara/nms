package com.example.nms.status;

import static com.example.nms.common.DeliveryStatus.DELIVERED;
import static com.example.nms.common.DeliveryStatus.EXPIRED;
import static com.example.nms.common.DeliveryStatus.FAILED;
import static com.example.nms.common.DeliveryStatus.PENDING;
import static com.example.nms.common.DeliveryStatus.RETRY_SCHEDULED;
import static com.example.nms.common.DeliveryStatus.SENDING;
import static com.example.nms.common.DeliveryStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.DeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** #11, A-21, A-22: every row of the architecture.md "Overall status" table. */
class OverallStatusTest {

  private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
  private static final Instant LATER = NOW.plus(Duration.ofHours(1));
  private static final Instant EARLIER = NOW.minus(Duration.ofHours(1));

  static Stream<Arguments> table() {
    return Stream.of(
        // Before scheduledAt
        row(LATER, List.of(PENDING), OverallStatus.SCHEDULED),
        row(LATER, List.of(PENDING, PENDING), OverallStatus.SCHEDULED),
        // Any PENDING, SENDING, RETRY_SCHEDULED
        row(null, List.of(PENDING), OverallStatus.IN_PROGRESS),
        row(EARLIER, List.of(PENDING), OverallStatus.IN_PROGRESS),
        row(NOW, List.of(PENDING), OverallStatus.IN_PROGRESS),
        row(null, List.of(SENDING, DELIVERED), OverallStatus.IN_PROGRESS),
        row(null, List.of(RETRY_SCHEDULED, SENT), OverallStatus.IN_PROGRESS),
        row(null, List.of(PENDING, FAILED), OverallStatus.IN_PROGRESS),
        // Not all PENDING: scheduledAt no longer decides
        row(LATER, List.of(SENDING, PENDING), OverallStatus.IN_PROGRESS),
        // Otherwise any SENT
        row(null, List.of(SENT), OverallStatus.AWAITING_CONFIRMATION),
        row(null, List.of(SENT, DELIVERED), OverallStatus.AWAITING_CONFIRMATION),
        row(null, List.of(SENT, FAILED), OverallStatus.AWAITING_CONFIRMATION),
        // All DELIVERED
        row(null, List.of(DELIVERED), OverallStatus.DELIVERED),
        row(EARLIER, List.of(DELIVERED, DELIVERED), OverallStatus.DELIVERED),
        // Some DELIVERED, rest failed or expired
        row(null, List.of(DELIVERED, FAILED), OverallStatus.PARTIALLY_DELIVERED),
        row(null, List.of(EXPIRED, DELIVERED), OverallStatus.PARTIALLY_DELIVERED),
        // None delivered, all EXPIRED
        row(null, List.of(EXPIRED), OverallStatus.EXPIRED),
        row(null, List.of(EXPIRED, EXPIRED), OverallStatus.EXPIRED),
        // None delivered, otherwise
        row(null, List.of(FAILED), OverallStatus.FAILED),
        row(null, List.of(FAILED, EXPIRED), OverallStatus.FAILED),
        // No deliveries, also before scheduledAt: nothing will ever be sent
        row(null, List.of(), OverallStatus.NOT_ROUTED),
        row(LATER, List.of(), OverallStatus.NOT_ROUTED));
  }

  @ParameterizedTest(name = "scheduledAt {0}, {1} -> {2}")
  @MethodSource("table")
  void derivesTheOverallStatus(
      Instant scheduledAt, List<DeliveryStatus> deliveries, OverallStatus expected) {
    assertThat(OverallStatus.derive(scheduledAt, NOW, deliveries)).isEqualTo(expected);
  }

  private static Arguments row(
      Instant scheduledAt, List<DeliveryStatus> deliveries, OverallStatus expected) {
    return Arguments.of(scheduledAt, deliveries, expected);
  }
}
