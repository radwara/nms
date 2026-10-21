package com.example.nms.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.nms.common.Channel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #7: requested channels are stored comma-separated in request order; absent is null. */
class ChannelListConverterTest {

  private final ChannelListConverter converter = new ChannelListConverter();

  @Test
  void joinsChannelsInRequestOrder() {
    assertThat(converter.convertToDatabaseColumn(List.of(Channel.SMS, Channel.EMAIL)))
        .isEqualTo("SMS,EMAIL");
    assertThat(converter.convertToDatabaseColumn(List.of(Channel.EMAIL))).isEqualTo("EMAIL");
  }

  @Test
  void splitsColumnInStoredOrder() {
    assertThat(converter.convertToEntityAttribute("SMS,EMAIL"))
        .containsExactly(Channel.SMS, Channel.EMAIL);
  }

  @Test
  void absentChannelsAreNull() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  void rejectsUnknownChannel() {
    assertThatThrownBy(() -> converter.convertToEntityAttribute("PIGEON"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
