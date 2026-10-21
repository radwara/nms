package com.example.nms.api;

import com.example.nms.intake.IntakeService;
import com.example.nms.intake.SubmitCommand;
import com.example.nms.intake.SubmitResult;
import com.example.nms.intake.TimeViolation;
import com.example.nms.security.SourceSystem;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Submit endpoint. Translates intake outcomes to HTTP: 202 new, 200 replay, 409 / 4091 key reused
 * with a different payload, 409 / 4092 key still in progress (with {@code Retry-After}), 422 /
 * 4221, 4222, 4223 time rules and unknown recipients together (new submissions only). Rejections
 * are audited by {@link SubmitRejectionAudit}.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

  /** Seconds a client should wait before retrying a key that is still in progress. */
  static final String RETRY_IN_PROGRESS_SECONDS = "1";

  private final SubmitRequestValidator validator;
  private final IntakeService intake;

  NotificationController(SubmitRequestValidator validator, IntakeService intake) {
    this.validator = validator;
    this.intake = intake;
  }

  /** The header is optional here so a missing key is reported with the body's errors. */
  @PostMapping
  ResponseEntity<SubmitResponse> submit(
      SourceSystem source,
      @RequestHeader(name = SubmitRequestValidator.IDEMPOTENCY_KEY, required = false)
          String idempotencyKey,
      @RequestBody JsonNode body,
      HttpServletResponse response) {
    SubmitCommand command = validator.validate(source, idempotencyKey, body);
    return switch (intake.submit(command)) {
      case SubmitResult.Created created ->
          ResponseEntity.status(HttpStatus.ACCEPTED)
              .body(SubmitResponse.of(created.notifications()));
      case SubmitResult.Replayed replayed ->
          ResponseEntity.ok(SubmitResponse.of(replayed.notifications()));
      case SubmitResult.KeyConflict conflict ->
          throw new ApiException(
              ApiError.of(
                  ErrorCode.IDEMPOTENCY_KEY_REUSED, SubmitRequestValidator.IDEMPOTENCY_KEY));
      case SubmitResult.InProgress inProgress -> {
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_IN_PROGRESS_SECONDS);
        throw new ApiException(
            ApiError.of(
                ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS, SubmitRequestValidator.IDEMPOTENCY_KEY));
      }
      case SubmitResult.Unprocessable unprocessable ->
          throw new ApiException(unprocessable(command, unprocessable));
    };
  }

  /** Time errors, then one error per unknown recipient by position: ids are not echoed. */
  private static List<ApiError> unprocessable(
      SubmitCommand command, SubmitResult.Unprocessable unprocessable) {
    List<ApiError> errors = new ArrayList<>();
    for (TimeViolation violation : unprocessable.timeViolations()) {
      errors.add(
          switch (violation) {
            case EXPIRES_AT_IN_PAST -> ApiError.of(ErrorCode.EXPIRES_AT_IN_PAST, "expiresAt");
            case SCHEDULED_AFTER_EXPIRY ->
                ApiError.of(ErrorCode.SCHEDULED_AFTER_EXPIRY, "scheduledAt");
          });
    }
    Set<String> unknown = Set.copyOf(unprocessable.unknownRecipientIds());
    for (int i = 0; i < command.recipients().size(); i++) {
      if (unknown.contains(command.recipients().get(i).userId())) {
        errors.add(ApiError.of(ErrorCode.UNKNOWN_RECIPIENT, "recipients[" + i + "].userId"));
      }
    }
    return errors;
  }
}
