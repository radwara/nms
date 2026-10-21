package com.example.nms.delivery;

import com.example.nms.common.Priority;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps {@link Priority} to its numeric code (smallint column). Applied explicitly per field. */
@Converter
public class PriorityCodeConverter implements AttributeConverter<Priority, Short> {

  @Override
  public Short convertToDatabaseColumn(Priority priority) {
    return priority == null ? null : priority.code();
  }

  @Override
  public Priority convertToEntityAttribute(Short code) {
    return code == null ? null : Priority.fromCode(code);
  }
}
