package com.example.nms.status;

import com.example.nms.audit.AuditEventType;
import com.example.nms.common.Channel;
import java.time.Instant;

/** One audit event of a notification's history; {@code detail} holds codes only. */
public record AuditEntry(Instant at, AuditEventType type, Channel channel, String detail) {}
