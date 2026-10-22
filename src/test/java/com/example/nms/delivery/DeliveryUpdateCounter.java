package com.example.nms.delivery;

import java.io.Serial;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts {@code update delivery} statements Hibernate prepares, and among them the set-based
 * postpone (the only one with a subquery on {@code notification}). Registered by property, so
 * Hibernate creates it; the counters are static.
 */
public class DeliveryUpdateCounter implements StatementInspector {

  @Serial private static final long serialVersionUID = 1L;

  static final AtomicInteger UPDATES = new AtomicInteger();
  static final AtomicInteger SET_BASED_POSTPONES = new AtomicInteger();

  static void reset() {
    UPDATES.set(0);
    SET_BASED_POSTPONES.set(0);
  }

  @Override
  public String inspect(String sql) {
    String lower = sql.toLowerCase(Locale.ROOT);
    if (lower.startsWith("update delivery")) {
      UPDATES.incrementAndGet();
      if (lower.contains("from notification")) {
        SET_BASED_POSTPONES.incrementAndGet();
      }
    }
    return sql;
  }
}
