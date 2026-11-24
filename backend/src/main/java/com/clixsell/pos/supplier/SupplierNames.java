package com.clixsell.pos.supplier;

/**
 * Supplier name rules (decision D17 / D26).
 *
 * <ul>
 *   <li>{@link #normalize}: trims and turns every run of white space (space, tab,
 *       line break, no-break space) into ONE space. "  Al   Quds " → "Al Quds".</li>
 *   <li>{@link #key}: the normalized name with only the English letters A–Z made
 *       lower case — exactly like SQLite's {@code COLLATE NOCASE}, which the
 *       existing UNIQUE (store_id, name) uses. So two names are "the same" here
 *       only if the database would also call them the same after normalizing.
 *       Arabic letters (أ/ا, ى/ي …) are NOT changed: that could change meaning.</li>
 * </ul>
 * Names already stored in the database are never rewritten by these rules.
 */
public final class SupplierNames {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 80;

    private SupplierNames() {
    }

    public static String normalize(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\s\\u00A0\\u2007\\u202F]+", " ").strip();
    }

    public static String key(String name) {
        String n = normalize(name);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            b.append(ch >= 'A' && ch <= 'Z' ? (char) (ch + 32) : ch);
        }
        return b.toString();
    }
}
