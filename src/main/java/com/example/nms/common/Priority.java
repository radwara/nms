package com.example.nms.common;

/**
 * Notification priority; drives worker claim order. The numeric {@link #code()} is stored on the
 * delivery so the poll sorts ascending (HIGH first) without a join.
 */
public enum Priority {
  HIGH(0),
  NORMAL(1),
  LOW(2);

  private final short code;

  Priority(int code) {
    this.code = (short) code;
  }

  public short code() {
    return code;
  }

  public static Priority fromCode(short code) {
    for (Priority priority : values()) {
      if (priority.code == code) {
        return priority;
      }
    }
    throw new IllegalArgumentException("Unknown priority code: " + code);
  }
}
