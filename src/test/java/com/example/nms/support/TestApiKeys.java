package com.example.nms.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Demo credentials configured in {@code src/test/resources/config/application.yml} (ADR-0001). Dev
 * and tests only; not secrets.
 */
public final class TestApiKeys {

  public static final String HEADER = "X-API-Key";

  /** demo-app, first of two keys (rotation). */
  public static final String DEMO1 = "nms_demo1_dev-only";

  /** demo-app, second of two keys (rotation). */
  public static final String DEMO2 = "nms_demo2_dev-only";

  /** other-app. */
  public static final String OTHER1 = "nms_other1_dev-only";

  public static final String WEBHOOK_HEADER = "X-Webhook-Token";

  /** Webhook credential for provider sim-email. */
  public static final String SIM_EMAIL = "sim-email-dev-only";

  /** Webhook credential for provider sim-sms. */
  public static final String SIM_SMS = "sim-sms-dev-only";

  private TestApiKeys() {}

  /** Lowercase hex SHA-256, computed independently of production code. */
  public static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
