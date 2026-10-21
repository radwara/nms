package com.example.nms.api;

import com.example.nms.intake.SubmittedNotification;
import java.util.List;
import java.util.UUID;

/** Body of 202 (new) and 200 (replay): one entry per recipient, in request order. */
record SubmitResponse(List<Item> notifications) {

  static final String ACCEPTED = "ACCEPTED";

  record Item(String recipientId, UUID notificationId, String status) {}

  static SubmitResponse of(List<SubmittedNotification> notifications) {
    return new SubmitResponse(
        notifications.stream()
            .map(n -> new Item(n.recipientId(), n.notificationId(), ACCEPTED))
            .toList());
  }
}
