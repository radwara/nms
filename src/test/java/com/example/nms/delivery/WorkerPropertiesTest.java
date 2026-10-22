package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** #10: worker settings that cannot work fail at startup. */
class WorkerPropertiesTest {

  private static final Duration SECOND = Duration.ofSeconds(1);
  private static final Duration LEASE = Duration.ofSeconds(30);

  @Test
  void zeroBatchSizeIsRejected() {
    assertThatThrownBy(() -> new WorkerProperties(true, SECOND, 0, LEASE, SECOND, 4, SECOND))
        .hasMessage("nms.worker.batch-size must be > 0");
  }

  @Test
  void zeroMaxAttemptsIsRejected() {
    assertThatThrownBy(() -> new WorkerProperties(true, SECOND, 20, LEASE, SECOND, 0, SECOND))
        .hasMessage("nms.worker.max-attempts must be > 0");
  }

  @Test
  void zeroLeaseIsRejected() {
    assertThatThrownBy(
            () -> new WorkerProperties(true, SECOND, 20, Duration.ZERO, SECOND, 4, SECOND))
        .hasMessage("nms.worker.lease must be longer than send-timeout");
  }

  @Test
  void zeroPollIntervalIsRejected() {
    assertThatThrownBy(
            () -> new WorkerProperties(true, Duration.ZERO, 20, LEASE, SECOND, 4, SECOND))
        .hasMessage("nms.worker.poll-interval must be > 0");
  }

  @Test
  void leaseNotLongerThanSendTimeoutIsRejected() {
    assertThatThrownBy(() -> new WorkerProperties(true, SECOND, 20, LEASE, LEASE, 4, SECOND))
        .hasMessage("nms.worker.lease must be longer than send-timeout");
  }

  @Test
  void zeroSendTimeoutIsRejected() {
    assertThatThrownBy(
            () -> new WorkerProperties(true, SECOND, 20, LEASE, Duration.ZERO, 4, SECOND))
        .hasMessage("nms.worker.send-timeout must be > 0");
  }
}
