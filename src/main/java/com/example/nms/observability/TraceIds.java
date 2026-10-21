package com.example.nms.observability;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;

/**
 * Trace ids for logs, independent of the web layer: the MDC key, id generation, and a scope that
 * binds an id to the current thread. Workers can open a scope the same way the HTTP filter does.
 */
public final class TraceIds {

  public static final String MDC_KEY = "traceId";

  private TraceIds() {}

  /** A new random trace id: 32 lowercase hex chars (W3C trace-id format). */
  public static String newId() {
    byte[] bytes = new byte[16];
    ThreadLocalRandom.current().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** The trace id bound to the current thread, or {@code null}. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }

  /** Binds {@code traceId} to the current thread until the scope is closed. */
  public static Scope open(String traceId) {
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, traceId);
    return new Scope(previous);
  }

  /** Restores the previous trace id (or none) on close. */
  public static final class Scope implements AutoCloseable {

    private final String previous;

    private Scope(String previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY, previous);
      }
    }
  }
}
