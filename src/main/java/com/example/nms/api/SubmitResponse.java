package com.example.nms.api;

import com.example.nms.intake.SubmittedNotification;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * Body of 202 (new) and 200 (replay): one entry per recipient, in request order. {@code status} is
 * {@code ACCEPTED} or {@code SUPPRESSED}; {@code duplicateOf} only appears on a suppressed item, so
 * a submission without suppressed recipients has the greenfield body.
 */
record SubmitResponse(List<Item> notifications) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Item(String recipientId, UUID notificationId, String status, UUID duplicateOf) {}

  static SubmitResponse of(List<SubmittedNotification> notifications) {
    return new SubmitResponse(
        notifications.stream()
            .map(
                n ->
                    new Item(
                        n.recipientId(), n.notificationId(), n.outcome().name(), n.duplicateOf()))
            .toList());
  }
}
