package com.example.nms.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** #7: delivery priority is stored as HIGH=0, NORMAL=1, LOW=2 so the poll sorts ascending. */
class PriorityCodeConverterTest {

  private final PriorityCodeConverter converter = new PriorityCodeConverter();

  @Test
  void mapsPrioritiesToDocumentedCodes() {
    assertThat(converter.convertToDatabaseColumn(Priority.HIGH)).isEqualTo((short) 0);
    assertThat(converter.convertToDatabaseColumn(Priority.NORMAL)).isEqualTo((short) 1);
    assertThat(converter.convertToDatabaseColumn(Priority.LOW)).isEqualTo((short) 2);
  }

  @Test
  void roundTripsEveryPriority() {
    for (Priority priority : Priority.values()) {
      assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(priority)))
          .isEqualTo(priority);
    }
  }

  @Test
  void passesNullThrough() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  void rejectsUnknownCode() {
    assertThatThrownBy(() -> converter.convertToEntityAttribute((short) 9))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
