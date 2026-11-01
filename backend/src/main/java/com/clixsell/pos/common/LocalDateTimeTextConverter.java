package com.clixsell.pos.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;

/**
 * Stores dates in SQLite as readable ISO text, e.g. "2026-11-01T22:37:07".
 * (SQLite has no date type; without this the driver writes milliseconds
 * that it cannot read back.)
 */
@Converter
public class LocalDateTimeTextConverter implements AttributeConverter<LocalDateTime, String> {

    @Override
    public String convertToDatabaseColumn(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    @Override
    public LocalDateTime convertToEntityAttribute(String text) {
        return text == null ? null : LocalDateTime.parse(text);
    }
}
