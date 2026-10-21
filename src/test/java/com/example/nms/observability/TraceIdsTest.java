package com.example.nms.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceIdsTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void newIdIs32LowercaseHexAndUnique() {
    assertThat(TraceIds.newId()).matches("[0-9a-f]{32}").isNotEqualTo(TraceIds.newId());
  }

  @Test
  void scopeBindsTheIdAndRemovesItOnClose() {
    TraceIds.Scope scope = TraceIds.open("abc");

    assertThat(TraceIds.current()).isEqualTo("abc");
    assertThat(MDC.get(TraceIds.MDC_KEY)).isEqualTo("abc");
    scope.close();
    assertThat(TraceIds.current()).isNull();
  }

  @Test
  void nestedScopeRestoresTheOuterId() {
    TraceIds.Scope outer = TraceIds.open("outer");
    TraceIds.Scope inner = TraceIds.open("inner");

    assertThat(TraceIds.current()).isEqualTo("inner");
    inner.close();
    assertThat(TraceIds.current()).isEqualTo("outer");
    outer.close();
    assertThat(TraceIds.current()).isNull();
  }
}
