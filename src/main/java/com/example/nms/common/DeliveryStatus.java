package com.example.nms.common;

/** Delivery lifecycle state (architecture.md "Delivery lifecycle"). */
public enum DeliveryStatus {
  PENDING,
  SENDING,
  RETRY_SCHEDULED,
  SENT,
  DELIVERED,
  FAILED,
  EXPIRED
}
