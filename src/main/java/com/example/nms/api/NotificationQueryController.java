package com.example.nms.api;

import com.example.nms.security.SourceSystem;
import com.example.nms.status.StatusQueries;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Status and audit reads, scoped to the caller's source system. The id is bound as a string and
 * parsed here, so an unknown id, a malformed one, and another source system's notification are all
 * the same 404 / 4041 (generic UUID binding would make malformed ids 400 / 4003).
 */
@RestController
@RequestMapping("/api/v1/notifications/{id}")
class NotificationQueryController {

  private final StatusQueries queries;

  NotificationQueryController(StatusQueries queries) {
    this.queries = queries;
  }

  @GetMapping
  StatusResponse status(SourceSystem source, @PathVariable("id") String id) {
    return queries
        .status(parse(id), source.name())
        .map(StatusResponse::of)
        .orElseThrow(NotificationQueryController::notFound);
  }

  @GetMapping("/audit")
  AuditResponse audit(SourceSystem source, @PathVariable("id") String id) {
    return queries
        .history(parse(id), source.name())
        .map(AuditResponse::of)
        .orElseThrow(NotificationQueryController::notFound);
  }

  private static UUID parse(String id) {
    return Uuids.parse(id).orElseThrow(NotificationQueryController::notFound);
  }

  private static ApiException notFound() {
    return new ApiException(ApiError.of(ErrorCode.NOTIFICATION_NOT_FOUND));
  }
}
