package com.example.nms.provider;

import com.example.nms.common.Channel;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code sim-email}: addresses {@code <send>.<callback>@...}; the send token is the part up to the
 * first dot, the callback token the rest up to {@code @}.
 */
@Component
public class SimulatedEmailProvider extends SimulatedProvider {

  public static final String NAME = "sim-email";

  SimulatedEmailProvider(SimulatedCallbacks callbacks) {
    super(NAME, Channel.EMAIL, "em-", callbacks);
  }

  @Override
  Optional<SimulatedSend> outcomeFor(String address) {
    int dot = address.indexOf('.');
    int at = address.indexOf('@');
    if (dot <= 0 || at < dot) {
      return Optional.empty();
    }
    return SimulatedSend.ofEmailToken(address.substring(0, dot));
  }

  @Override
  Optional<SimulatedCallback> callbackFor(String address) {
    int dot = address.indexOf('.');
    int at = address.indexOf('@');
    if (dot <= 0 || at < dot) {
      return Optional.empty();
    }
    return SimulatedCallback.ofEmailToken(address.substring(dot + 1, at));
  }
}
