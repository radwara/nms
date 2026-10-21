package com.example.nms.api;

import com.example.nms.intake.IntakeService;
import com.example.nms.intake.SubmitCommand;
import com.example.nms.intake.SubmitResult;
import com.example.nms.security.SourceSystem;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
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
 * with a different payload, 422 / 4223 unknown recipients. Rejections are audited by {@link
 * SubmitRejectionAudit}.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

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
      @RequestBody JsonNode body) {
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
      case SubmitResult.UnknownRecipients unknown ->
          throw new ApiException(unknownRecipients(command, unknown));
    };
  }

  /** One error per unknown recipient, by position: the ids themselves are not echoed. */
  private static List<ApiError> unknownRecipients(
      SubmitCommand command, SubmitResult.UnknownRecipients unknown) {
    Set<String> ids = Set.copyOf(unknown.recipientIds());
    return IntStream.range(0, command.recipients().size())
        .filter(i -> ids.contains(command.recipients().get(i).userId()))
        .mapToObj(i -> ApiError.of(ErrorCode.UNKNOWN_RECIPIENT, "recipients[" + i + "].userId"))
        .toList();
  }
}
