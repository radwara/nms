package com.example.nms.delivery;

import static com.example.nms.support.TestEntities.NOW;
import static com.example.nms.support.TestEntities.delivery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.example.nms.common.Channel;
import com.example.nms.common.DeliveryStatus;
import com.example.nms.common.Priority;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** #7: a new delivery is a due PENDING row with every required field set. */
class DeliveryTest {

  private static final UUID ID = UUID.randomUUID();
  private static final UUID NOTIFICATION_ID = UUID.randomUUID();

  @Test
  void newDeliveryIsPendingAndDue() {
    Delivery delivery = delivery(NOTIFICATION_ID, Channel.EMAIL);

    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(delivery.getAttemptCount()).isZero();
    assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW);
    assertThat(delivery.getUpdatedAt()).isEqualTo(delivery.getCreatedAt());
  }

  @Test
  void rejectsNullNextAttemptAt() {
    assertThatNullPointerException()
        .isThrownBy(() -> create(Priority.HIGH, RoutingReason.REQUESTED, null))
        .withMessage("nextAttemptAt");
  }

  @Test
  void rejectsNullRequiredFields() {
    assertThatNullPointerException()
        .isThrownBy(() -> create(null, RoutingReason.REQUESTED, NOW))
        .withMessage("priority");
    assertThatNullPointerException()
        .isThrownBy(() -> create(Priority.HIGH, null, NOW))
        .withMessage("routingReason");
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new Delivery(
                    null,
                    NOTIFICATION_ID,
                    Channel.EMAIL,
                    "sim-email",
                    Priority.HIGH,
                    RoutingReason.REQUESTED,
                    NOW,
                    NOW))
        .withMessage("id");
  }

  /** Direct constructor call: the fixture always passes valid values. */
  private static Delivery create(Priority priority, RoutingReason reason, Instant nextAttemptAt) {
    return new Delivery(
        ID, NOTIFICATION_ID, Channel.EMAIL, "sim-email", priority, reason, nextAttemptAt, NOW);
  }
}
