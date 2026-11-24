package com.clixsell.pos.common;

import java.util.Map;
import java.util.Set;

/**
 * Strict reading of a JSON object body (decision D25), used by the Supplier
 * Management and purchase endpoints only: a field the endpoint does not accept
 * (for example {@code storeId}, {@code active} in an edit, or a cost) is refused
 * with 400 UNKNOWN_FIELD instead of being silently ignored, and a field of the
 * wrong JSON type is refused with 400 VALIDATION_FAILED.
 */
public final class StrictJson {

    private StrictJson() {
    }

    public static void requireOnly(Map<String, Object> body, Set<String> allowed) {
        if (body == null) throw new ValidationException(Map.of("body", "A JSON object is required"));
        for (String field : body.keySet()) {
            if (!allowed.contains(field)) throw new UnknownFieldException(field);
        }
    }

    /** The text value of {@code field}, or null when it is missing or JSON null. */
    public static String text(Map<String, Object> body, String field) {
        Object v = body.get(field);
        if (v == null) return null;
        if (!(v instanceof String s)) throw new ValidationException(Map.of(field, "Must be text"));
        return s;
    }

    /** A required true / false value. */
    public static boolean requiredBoolean(Map<String, Object> body, String field) {
        Object v = body.get(field);
        if (!(v instanceof Boolean b)) throw new ValidationException(Map.of(field, "Must be true or false"));
        return b;
    }
}
