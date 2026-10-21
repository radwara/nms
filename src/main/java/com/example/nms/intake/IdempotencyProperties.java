package com.example.nms.intake;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retention how long an accepted key is honored (ADR-0002, A-36); afterwards the key may
 *     create a new submission
 */
@ConfigurationProperties("nms.idempotency")
record IdempotencyProperties(@DefaultValue("7d") Duration retention) {

  IdempotencyProperties {
    if (retention.isNegative() || retention.isZero()) {
      throw new IllegalArgumentException("nms.idempotency.retention must be positive");
    }
  }
}
