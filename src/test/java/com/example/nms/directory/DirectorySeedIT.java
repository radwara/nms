package com.example.nms.directory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

/** #7: the seeded directory covers the documented shapes and simulated outcomes. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DirectorySeedIT {

  private static final Pattern EMAIL = Pattern.compile("([a-z0-9]+)\\.([a-z]+)@sim\\.test");
  private static final Pattern PHONE = Pattern.compile("\\+1555010(\\d{2})(\\d{2})");

  /** Send token to SMS send code, as documented in architecture.md. */
  private static final Map<String, String> SEND_CODES =
      Map.of(
          "ok", "00",
          "transient", "01",
          "timeout", "02",
          "ratelimit", "03",
          "reject", "04",
          "invalid", "05",
          "autherror", "06",
          "flaky2", "12");

  private static final Set<String> CALLBACK_TOKENS =
      Set.of("delivered", "bounce", "failed", "silent");
  private static final Set<String> SMS_CALLBACK_CODES = Set.of("00", "02", "09");

  @Autowired private RecipientRepository recipients;

  @Autowired private ChannelPreferenceRepository preferences;

  @Test
  void recipientWithBothChannels() {
    Recipient recipient = recipients.findById("user-ok").orElseThrow();

    assertThat(recipient.getEmail()).isEqualTo("ok.delivered@sim.test");
    assertThat(recipient.getPhone()).isEqualTo("+15550100000");
    assertThat(preferences.findByIdRecipientId("user-ok")).isEmpty();
  }

  @Test
  void recipientWithEmailOnly() {
    Recipient recipient = recipients.findById("user-email-only").orElseThrow();

    assertThat(recipient.getEmail()).isNotNull();
    assertThat(recipient.getPhone()).isNull();
  }

  @Test
  void recipientOptedOutOfSms() {
    Recipient recipient = recipients.findById("user-sms-optout").orElseThrow();

    assertThat(recipient.getPhone()).isNotNull();
    assertThat(preferences.findById(new ChannelPreferenceId("user-sms-optout", Channel.SMS)))
        .hasValueSatisfying(p -> assertThat(p.isEnabled()).isFalse());
    assertThat(preferences.findById(new ChannelPreferenceId("user-sms-optout", Channel.EMAIL)))
        .hasValueSatisfying(p -> assertThat(p.isEnabled()).isTrue());
  }

  @Test
  void everySendOutcomeIsSeededOnBothChannels() {
    List<Recipient> all = recipients.findAll();

    Set<String> emailSendTokens =
        all.stream()
            .map(Recipient::getEmail)
            .filter(Objects::nonNull)
            .map(email -> match(EMAIL, email).group(1))
            .collect(Collectors.toSet());
    Set<String> smsSendCodes =
        all.stream()
            .map(Recipient::getPhone)
            .filter(Objects::nonNull)
            .map(phone -> match(PHONE, phone).group(1))
            .collect(Collectors.toSet());

    assertThat(emailSendTokens).isEqualTo(SEND_CODES.keySet());
    assertThat(smsSendCodes).containsExactlyInAnyOrderElementsOf(SEND_CODES.values());
  }

  @Test
  void everyCallbackOutcomeIsSeeded() {
    List<Recipient> all = recipients.findAll();

    Set<String> emailCallbacks =
        all.stream()
            .map(Recipient::getEmail)
            .filter(Objects::nonNull)
            .map(email -> match(EMAIL, email).group(2))
            .collect(Collectors.toSet());
    Set<String> smsCallbacks =
        all.stream()
            .map(Recipient::getPhone)
            .filter(Objects::nonNull)
            .map(phone -> match(PHONE, phone).group(2))
            .collect(Collectors.toSet());

    assertThat(emailCallbacks).isEqualTo(CALLBACK_TOKENS);
    assertThat(smsCallbacks).isEqualTo(SMS_CALLBACK_CODES);
  }

  @Test
  void emailAndPhoneOfOneRecipientEncodeTheSameSendOutcome() {
    assertThat(recipients.findAll())
        .filteredOn(r -> r.getEmail() != null && r.getPhone() != null)
        .allSatisfy(
            r ->
                assertThat(match(PHONE, r.getPhone()).group(1))
                    .isEqualTo(SEND_CODES.get(match(EMAIL, r.getEmail()).group(1))));
  }

  private static Matcher match(Pattern pattern, String value) {
    Matcher matcher = pattern.matcher(value);
    assertThat(matcher.matches()).as("%s matches %s", value, pattern).isTrue();
    return matcher;
  }
}
