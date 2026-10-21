package com.example.nms.provider;

import com.example.nms.common.Channel;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code sim-email}: the send token is the address up to the first dot, {@code <send>.<cb>@...}.
 */
@Component
public class SimulatedEmailProvider extends SimulatedProvider {

  public static final String NAME = "sim-email";

  SimulatedEmailProvider() {
    super(NAME, Channel.EMAIL, "em-");
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
}
