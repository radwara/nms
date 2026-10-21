package com.example.nms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApiErrorsTest {

  @Test
  void noErrorsThrowsNothing() {
    assertThatCode(() -> new ApiErrors().throwIfAny()).doesNotThrowAnyException();
  }

  @Test
  void throwsEveryCollectedError() {
    ApiErrors errors =
        new ApiErrors()
            .add(ErrorCode.FIELD_MISSING, "eventId")
            .add(ErrorCode.INVALID_VALUE, "severity");

    assertThatThrownBy(errors::throwIfAny)
        .isInstanceOfSatisfying(
            ApiException.class,
            ex ->
                assertThat(ex.errors())
                    .extracting(ApiError::field)
                    .containsExactly("eventId", "severity"));
  }

  @Test
  void badRequestErrorsWinOverUnprocessableOnes() {
    ApiErrors errors =
        new ApiErrors()
            .add(ErrorCode.EXPIRES_AT_IN_PAST, "expiresAt")
            .add(ErrorCode.FIELD_MISSING, "eventId");

    assertThatThrownBy(errors::throwIfAny)
        .isInstanceOfSatisfying(
            ApiException.class,
            ex ->
                assertThat(ex.errors())
                    .extracting(ApiError::code)
                    .containsExactly(ErrorCode.FIELD_MISSING));
  }

  @Test
  void unprocessableErrorsAreThrownWhenNoBadRequestErrorExists() {
    ApiErrors errors =
        new ApiErrors()
            .add(ErrorCode.EXPIRES_AT_IN_PAST, "expiresAt")
            .add(ErrorCode.UNKNOWN_RECIPIENT, "recipients[1].userId");

    assertThatThrownBy(errors::throwIfAny)
        .isInstanceOfSatisfying(
            ApiException.class,
            ex ->
                assertThat(ex.errors())
                    .extracting(ApiError::code)
                    .containsExactly(ErrorCode.EXPIRES_AT_IN_PAST, ErrorCode.UNKNOWN_RECIPIENT));
  }

  @Test
  void rejectsCodesOtherThan400And422() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ApiErrors().add(ErrorCode.NOTIFICATION_NOT_FOUND, null));
  }
}
