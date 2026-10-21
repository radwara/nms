package com.example.nms.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.dao.DataIntegrityViolationException;

/** Asserts that a write fails on a specific named constraint, not just any integrity error. */
public final class ConstraintAssertions {

  private ConstraintAssertions() {}

  public static void assertViolates(String constraintName, ThrowingCallable write) {
    assertThatThrownBy(write)
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            e ->
                assertThat(causeChainMentions(e, constraintName))
                    .as("cause chain names constraint %s", constraintName)
                    .isTrue());
  }

  private static boolean causeChainMentions(Throwable error, String constraintName) {
    String expected = constraintName.toLowerCase(Locale.ROOT);
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().toLowerCase(Locale.ROOT).contains(expected)) {
        return true;
      }
    }
    return false;
  }
}
