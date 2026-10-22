package com.example.nms.intake;

import java.util.UUID;

/** A dedup lookup row: an original notification's recipient and id. */
record EventOriginal(String recipientId, UUID id) {}
