package com.example.nms.delivery;

import com.example.nms.common.Channel;
import java.util.List;
import java.util.UUID;

/**
 * The deliveries one submission queued, published inside its transaction. An after-commit listener
 * may dispatch them at once (ADR-0008); without one, nothing happens and the poller sends them.
 */
public record DeliveriesQueued(List<Queued> deliveries) {

  public DeliveriesQueued {
    deliveries = List.copyOf(deliveries);
  }

  /** Ids and channel only: no content or addresses. */
  public record Queued(UUID id, Channel channel) {}
}
