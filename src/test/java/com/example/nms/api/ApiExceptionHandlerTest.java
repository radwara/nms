package com.example.nms.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bean Validation is not on the classpath yet, so the validation exceptions are built by hand to
 * pin down their mapping: missing-value constraints are 4004, anything else 4003, per field.
 */
class ApiExceptionHandlerTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final ApiExceptionHandler handler =
      new ApiExceptionHandler(new ErrorResponseWriter(jsonMapper));

  record Body(String eventId, String severity) {}

  @SuppressWarnings("unused")
  void endpoint(Body body, @RequestHeader("Idempotency-Key") String key) {}

  @Test
  void methodArgumentNotValidReportsEveryFieldWithItsCode() throws Exception {
    BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Body(null, "x"), "body");
    result.addError(fieldError("eventId", "NotBlank"));
    result.addError(fieldError("severity", "Pattern"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.notValid(new MethodArgumentNotValidException(parameter(0), result), response);

    JsonNode body = jsonMapper.readTree(response.getContentAsByteArray());
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(body.at("/errors/0/code").asInt()).isEqualTo(4004);
    assertThat(body.at("/errors/0/field").asString()).isEqualTo("eventId");
    assertThat(body.at("/errors/1/code").asInt()).isEqualTo(4003);
    assertThat(body.at("/errors/1/field").asString()).isEqualTo("severity");
  }

  @Test
  void handlerMethodValidationUsesTheNameTheClientSent() throws Exception {
    ParameterValidationResult header =
        new ParameterValidationResult(
            parameter(1),
            "",
            List.<MessageSourceResolvable>of(
                new DefaultMessageSourceResolvable(new String[] {"NotBlank"}, "blank")),
            null,
            null,
            null,
            (error, type) -> null);
    MethodValidationResult result = MethodValidationResult.create(this, method(), List.of(header));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.methodNotValid(new HandlerMethodValidationException(result), response);

    JsonNode body = jsonMapper.readTree(response.getContentAsByteArray());
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(body.at("/errors/0/code").asInt()).isEqualTo(4004);
    assertThat(body.at("/errors/0/field").asString()).isEqualTo("Idempotency-Key");
  }

  private static FieldError fieldError(String field, String constraint) {
    return new FieldError(
        "body",
        field,
        null,
        false,
        new String[] {constraint + ".body." + field, constraint},
        null,
        "message");
  }

  private static Method method() throws NoSuchMethodException {
    return ApiExceptionHandlerTest.class.getDeclaredMethod("endpoint", Body.class, String.class);
  }

  private static MethodParameter parameter(int index) throws NoSuchMethodException {
    return new MethodParameter(method(), index);
  }
}
