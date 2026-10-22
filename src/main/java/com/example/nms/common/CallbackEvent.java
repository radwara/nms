package com.example.nms.common;

/**
 * Final outcome a provider reports by callback (ADR-0003). {@code BOUNCED} is stored as delivery
 * {@code FAILED} with reason {@code BOUNCED}.
 */
public enum CallbackEvent {
  DELIVERED,
  BOUNCED,
  FAILED
}
