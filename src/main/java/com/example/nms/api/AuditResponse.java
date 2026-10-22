package com.example.nms.api;

import com.example.nms.audit.AuditEventType;
import com.example.nms.common.Channel;
import com.example.nms.status.AuditEntry;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Body of {@code GET /notifications/{id}/audit}: the history in order, codes only. */
record AuditResponse(List<Event> events) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Event(Instant at, AuditEventType type, Channel channel, String detail) {}

  static AuditResponse of(List<AuditEntry> entries) {
    return new AuditResponse(
        entries.stream().map(e -> new Event(e.at(), e.type(), e.channel(), e.detail())).toList());
  }
}
