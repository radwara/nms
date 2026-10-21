package com.example.nms.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class RedactedThrowableTest {

  @Test
  void keepsTypesAndStackFramesButNoMessages() {
    IllegalStateException cause = new IllegalStateException("submitted-value-in-cause");
    RuntimeException original = new IllegalArgumentException("submitted-value", cause);

    RedactedThrowable redacted = RedactedThrowable.of(original);

    assertThat(redacted.getMessage()).isEqualTo(IllegalArgumentException.class.getName());
    assertThat(redacted.getCause().getMessage()).isEqualTo(IllegalStateException.class.getName());
    assertThat(redacted.getStackTrace()).isEqualTo(original.getStackTrace());
    StringWriter printed = new StringWriter();
    redacted.printStackTrace(new PrintWriter(printed));
    assertThat(printed.toString()).doesNotContain("submitted-value");
  }

  @Test
  void boundsLongCauseChains() {
    Throwable chain = new RuntimeException("root");
    for (int i = 0; i < 50; i++) {
      chain = new RuntimeException("level " + i, chain);
    }

    int depth = 0;
    for (Throwable t = RedactedThrowable.of(chain); t != null; t = t.getCause()) {
      depth++;
    }
    assertThat(depth).isEqualTo(11);
  }
}
