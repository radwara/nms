package com.example.nms.support;

/** Demo keys configured in {@code src/test/resources/config/application.yml} (ADR-0001). */
public final class TestApiKeys {

  public static final String HEADER = "X-API-Key";

  /** demo-app, first of two keys (rotation). */
  public static final String DEMO1 = "nms_demo1_dev-only";

  /** demo-app, second of two keys (rotation). */
  public static final String DEMO2 = "nms_demo2_dev-only";

  /** other-app. */
  public static final String OTHER1 = "nms_other1_dev-only";

  private TestApiKeys() {}
}
