package com.clixsell.pos.purchase;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Purchase-order times are stored in ONE fixed text form, always with seconds:
 * "2026-11-24T10:00:00". (LocalDateTime.toString() leaves out ":00" seconds, which makes
 * text comparisons such as "closed in the last 14 days" unreliable.)
 */
@Converter
public class PurchaseTime implements AttributeConverter<LocalDateTime, String> {

    public static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }

    public static String text(LocalDateTime t) {
        return t == null ? null : FORMAT.format(t);
    }

    @Override
    public String convertToDatabaseColumn(LocalDateTime value) {
        return text(value);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(String text) {
        return text == null ? null : LocalDateTime.parse(text);
    }
}
