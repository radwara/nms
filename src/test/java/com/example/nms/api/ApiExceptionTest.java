package com.example.nms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionTest {

  @Test
  void carriesErrorsAndTheirSharedStatus() {
    ApiException ex =
        new ApiException(
            ApiError.of(ErrorCode.FIELD_MISSING, "eventId"),
            new ApiError(ErrorCode.INVALID_VALUE, "type", "Unknown type"));

    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(ex.errors()).extracting(ApiError::field).containsExactly("eventId", "type");
    assertThat(ex.getMessage()).isEqualTo("API error 4004, 4003");
  }

  @Test
  void rejectsNoErrors() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ApiException(List.of()));
  }

  @Test
  void rejectsErrorsWithDifferentStatuses() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ApiException(
                    ApiError.of(ErrorCode.FIELD_MISSING),
                    ApiError.of(ErrorCode.UNKNOWN_RECIPIENT)));
  }
}
