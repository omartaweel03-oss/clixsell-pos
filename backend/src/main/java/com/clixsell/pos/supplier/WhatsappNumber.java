package com.clixsell.pos.supplier;

/**
 * A supplier's WhatsApp number in international format, stored as digits only
 * (country code first, no "+", no leading zero), e.g. 970599123456.
 *
 * <p>Accepted input: digits with spaces, "-", ".", "(", ")" and an optional
 * leading "+" or "00" (both mean "international"). A local number such as
 * 0599123456 is refused: WhatsApp needs the country code.
 */
public final class WhatsappNumber {

    public static final int MIN_DIGITS = 8;
    public static final int MAX_DIGITS = 15;

    private WhatsappNumber() {
    }

    /**
     * The stored form of {@code input}: {@code null} for an empty input (no number),
     * otherwise the digits.
     *
     * @throws IllegalArgumentException when the input is not a valid international number
     */
    public static String parse(String input) {
        if (input == null || input.isBlank()) return null;
        String s = input.strip().replaceAll("[\\s\\-.()\\u00A0]", "");
        if (s.startsWith("+")) s = s.substring(1);
        else if (s.startsWith("00")) s = s.substring(2);
        if (!s.matches("[0-9]+") || s.length() < MIN_DIGITS || s.length() > MAX_DIGITS || s.charAt(0) == '0') {
            throw new IllegalArgumentException(input);
        }
        return s;
    }
}
