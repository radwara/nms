package com.example.nms.intake;

import com.example.nms.common.Channel;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps a channel list to a comma-separated column, e.g. {@code SMS,EMAIL}, keeping request order. A
 * null or empty list (channels not requested) is stored as null. Applied explicitly per field.
 */
@Converter
public class ChannelListConverter implements AttributeConverter<List<Channel>, String> {

  private static final String SEPARATOR = ",";

  @Override
  public String convertToDatabaseColumn(List<Channel> channels) {
    if (channels == null || channels.isEmpty()) {
      return null;
    }
    return channels.stream().map(Channel::name).collect(Collectors.joining(SEPARATOR));
  }

  @Override
  public List<Channel> convertToEntityAttribute(String column) {
    if (column == null || column.isBlank()) {
      return null;
    }
    return Arrays.stream(column.split(SEPARATOR)).map(Channel::valueOf).toList();
  }
}
