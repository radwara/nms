package com.example.nms.intake;

import java.util.UUID;

/** The id handed back for one recipient of a submission. */
public record SubmittedNotification(String recipientId, UUID notificationId) {}
