package com.example.nms.provider;

import com.example.nms.common.Channel;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** {@code sim-sms}: numbers {@code +1555010SSCC}, SS = send code, CC = callback code. */
@Component
public class SimulatedSmsProvider extends SimulatedProvider {

  public static final String NAME = "sim-sms";

  private static final Pattern NUMBER = Pattern.compile("\\+1555010(\\d{2})(\\d{2})");

  SimulatedSmsProvider() {
    super(NAME, Channel.SMS, "sm-");
  }

  @Override
  Optional<SimulatedSend> outcomeFor(String address) {
    Matcher matcher = NUMBER.matcher(address);
    return matcher.matches() ? SimulatedSend.ofSmsCode(matcher.group(1)) : Optional.empty();
  }
}
