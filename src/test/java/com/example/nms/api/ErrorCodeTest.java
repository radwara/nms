package com.example.nms.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatusCode;

class ErrorCodeTest {

  @ParameterizedTest
  @CsvSource({
    "400, 4000, Bad request",
    "404, 4040, Resource not found",
    "405, 4050, Method not allowed",
    "406, 4060, Not acceptable",
    "409, 4000, Conflict",
    "413, 4000, Content Too Large",
    "500, 5000, Internal error",
    "503, 5000, Service Unavailable"
  })
  void genericCodeForAStatusIsStatusTimesTenIfCataloguedElse4000Or5000(
      int status, int code, String message) {
    ApiError error = ApiError.forStatus(HttpStatusCode.valueOf(status));

    assertThat(error.code().code()).isEqualTo(code);
    assertThat(error.message()).isEqualTo(message);
    assertThat(error.field()).isNull();
  }

  @Test
  void codesAreUnique() {
    assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code)).doesNotHaveDuplicates();
  }
}
