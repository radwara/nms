package com.example.nms.provider;

import com.example.nms.common.CallbackEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Disabled {@link SimulatedCallbacks} that records what the providers ask it to schedule. */
class RecordingCallbacks extends SimulatedCallbacks {

  /** One first acceptance; {@code event} is empty when the address asks for no callback. */
  record Call(String provider, UUID deliveryId, String messageId, Optional<CallbackEvent> event) {}

  final List<Call> calls = new CopyOnWriteArrayList<>();

  RecordingCallbacks() {
    super(new SimulatedCallbackProperties(false, Duration.ZERO, null, Map.of()), Clock.systemUTC());
  }

  @Override
  void accepted(
      String provider,
      UUID deliveryId,
      String providerMessageId,
      Optional<SimulatedCallback> callback) {
    calls.add(
        new Call(
            provider, deliveryId, providerMessageId, callback.flatMap(SimulatedCallback::event)));
  }
}
