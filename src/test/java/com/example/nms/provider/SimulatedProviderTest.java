package com.example.nms.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.provider.SendResult.Accepted;
import com.example.nms.provider.SendResult.Failed;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** #10: the simulated providers follow the address convention and dedupe on deliveryId. */
class SimulatedProviderTest {

  private final SimulatedEmailProvider email = new SimulatedEmailProvider();
  private final SimulatedSmsProvider sms = new SimulatedSmsProvider();

  @ParameterizedTest
  @CsvSource({
    "transient.delivered@sim.test, +15550100100, TRANSIENT, 503",
    "timeout.delivered@sim.test, +15550100200, TIMEOUT, 504",
    "ratelimit.delivered@sim.test, +15550100300, RATE_LIMITED, 429",
    "reject.delivered@sim.test, +15550100400, PERMANENT_REJECTION, 422",
    "invalid.delivered@sim.test, +15550100500, INVALID_RECIPIENT, 400",
    "autherror.delivered@sim.test, +15550100600, AUTH_ERROR, 401",
    "nosuchtoken.delivered@sim.test, +15550109900, INVALID_RECIPIENT, 400",
    "not-the-convention@sim.test, +4915112345678, INVALID_RECIPIENT, 400"
  })
  void sendTokenAndCodeSelectTheFailure(
      String emailAddress, String phone, ErrorCategory category, String code) {
    assertThat(email.send(request(emailAddress)))
        .isEqualTo(new Failed(category, code, retryAfter(category)));
    assertThat(sms.send(request(phone)))
        .isEqualTo(new Failed(category, code, retryAfter(category)));
  }

  @Test
  void okIsAcceptedWithAProviderMessageId() {
    assertThat(email.send(request("ok.delivered@sim.test")))
        .isInstanceOfSatisfying(
            Accepted.class, a -> assertThat(a.providerMessageId()).startsWith("em-"));
    assertThat(sms.send(request("+15550100000")))
        .isInstanceOfSatisfying(
            Accepted.class, a -> assertThat(a.providerMessageId()).startsWith("sm-"));
  }

  @Test
  void rateLimitedCarriesRetryAfter() {
    Failed failed = (Failed) email.send(request("ratelimit.delivered@sim.test"));

    assertThat(failed.retryAfter()).contains(SimulatedProvider.RETRY_AFTER);
  }

  @Test
  void resendAfterAcceptanceReturnsTheSameMessageAndIsNotANewMessage() {
    SendRequest request = request("ok.delivered@sim.test");

    SendResult first = email.send(request);
    SendResult second = email.send(request);

    assertThat(second).isEqualTo(first);
    assertThat(email.acceptedMessages()).isEqualTo(1);
    assertThat(email.sendCalls()).isEqualTo(2);
  }

  @Test
  void otherDeliveryToTheSameAddressIsANewMessage() {
    email.send(request("ok.delivered@sim.test"));
    email.send(request("ok.delivered@sim.test"));

    assertThat(email.acceptedMessages()).isEqualTo(2);
  }

  @Test
  void flaky2FailsTwicePerDeliveryThenAccepts() {
    SendRequest request = request("flaky2.delivered@sim.test");
    SendRequest other = request("+15550101200");

    assertThat(email.send(request)).isInstanceOf(Failed.class);
    assertThat(email.send(request)).isInstanceOf(Failed.class);
    assertThat(email.send(request)).isInstanceOf(Accepted.class);
    assertThat(email.send(request)).isInstanceOf(Accepted.class);
    assertThat(sms.send(other)).isEqualTo(new Failed(ErrorCategory.TRANSIENT, "503"));
  }

  @Test
  void requestToStringHidesAddressAndContent() {
    SendRequest request =
        new SendRequest(UUID.randomUUID(), "ok.delivered@sim.test", "secret subject", "secret");

    assertThat(request.toString())
        .contains(request.deliveryId().toString())
        .doesNotContain("sim.test", "secret");
  }

  @Test
  void onlyTransientTimeoutAndRateLimitedAreRetryable() {
    assertThat(ErrorCategory.values())
        .filteredOn(ErrorCategory::retryable)
        .containsExactly(
            ErrorCategory.TRANSIENT, ErrorCategory.TIMEOUT, ErrorCategory.RATE_LIMITED);
  }

  private static SendRequest request(String address) {
    return new SendRequest(UUID.randomUUID(), address, "subject", "body");
  }

  private static Optional<Duration> retryAfter(ErrorCategory category) {
    return category == ErrorCategory.RATE_LIMITED
        ? Optional.of(SimulatedProvider.RETRY_AFTER)
        : Optional.empty();
  }
}
