package com.clixsell.pos.purchase;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds the supplier message of a purchase order (UR-M15 / SR28): store name, order number,
 * date, supplier name, and for every line the product name, barcode and quantity — taken from
 * the order's snapshots. It never contains a price, a cost, a total, a profit, a risk score, the
 * manager's internal order note or anything about logins.
 *
 * <p>Length is counted in Unicode code points, the unit SQLite's length() uses for the V6 limit
 * (message_text 1..2000): Java's String.length() counts UTF-16 units and would count an emoji as
 * 2. The text is cleaned before it is counted (the counted text is exactly the stored text):
 * CR/LF → LF, control characters and NUL removed (SQLite stops counting at NUL), unpaired
 * surrogates replaced by U+FFFD, and bidirectional control characters in names removed so a
 * name cannot visually reorder a quantity. Names, numbers and the date are wrapped in Unicode
 * isolates (FSI … PDI) so they read correctly inside Arabic text.
 *
 * <p>Nothing is ever cut: a message that does not fit is refused, never shortened.
 */
public final class WhatsappMessageBuilder {

    public static final int MAX_LENGTH = 2000;
    /** Store.MAX_NAME_LENGTH: the only part of the message that can still change after approval. */
    public static final int MAX_STORE_NAME = 80;
    public static final List<String> LANGUAGES = List.of("ar", "en");

    private static final char FSI = '⁨';
    private static final char PDI = '⁩';

    public record Line(Long productId, String productName, String barcode, int quantity) {
    }

    public record Input(String language, String storeName, String orderNumber, LocalDate date, String supplierName,
                        List<Line> lines) {
    }

    public record LineLength(Long productId, String productName, int length) {
    }

    /**
     * length: the message as it would be now (code points). maxPossibleLength: the same message
     * with the longest possible store name — after approval nothing else in it can change, so if
     * this fits, generating the message later can never fail for length.
     */
    public record Report(String language, int length, int maxPossibleLength, int limit, boolean fits,
                         boolean alwaysFits, List<LineLength> longestLines, int minLinesToRemove) {
    }

    private WhatsappMessageBuilder() {
    }

    public static String text(Input in) {
        boolean ar = "ar".equals(in.language());
        if (!LANGUAGES.contains(in.language())) throw new IllegalArgumentException("language " + in.language());
        StringBuilder b = new StringBuilder();
        b.append(ar ? "طلب شراء رقم: " : "Purchase order: ").append(iso(in.orderNumber())).append('\n');
        b.append(ar ? "من: " : "From: ").append(iso(in.storeName())).append('\n');
        b.append(ar ? "إلى: " : "To: ").append(iso(in.supplierName())).append('\n');
        b.append(ar ? "التاريخ: " : "Date: ").append(iso(in.date().toString())).append('\n');
        b.append('\n').append(ar ? "المطلوب:" : "Items:").append('\n');
        int n = 1;
        for (Line l : in.lines()) b.append(line(ar, n++, l)).append('\n');
        b.append('\n').append(ar ? "الرجاء تأكيد الطلب والكميات المتوفرة. شكراً." : "Please confirm this order and the available quantities. Thank you.");
        return b.toString();
    }

    private static String line(boolean ar, int n, Line l) {
        return n + ") " + iso(l.productName()) + (ar ? " | الباركود: " : " | Barcode: ") + iso(l.barcode())
                + (ar ? " | الكمية: " : " | Qty: ") + iso(Integer.toString(l.quantity()));
    }

    /** One user-provided value on one line, cleaned and isolated. */
    private static String iso(String value) {
        return FSI + clean(value == null ? "" : value) + PDI;
    }

    /**
     * Single-line cleaning: line breaks and tabs → space, other control characters (incl. NUL),
     * bidi controls removed, unpaired surrogates → U+FFFD, then trimmed.
     */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                b.append(c).append(s.charAt(++i));
                continue;
            }
            if (Character.isSurrogate(c)) {
                b.append('�');
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\t' || c == '\u2028' || c == '\u2029') {
                b.append(' ');
                continue;
            }
            if (Character.getType(c) == Character.CONTROL || isBidiControl(c)) continue;
            b.append(c);
        }
        return b.toString().strip();
    }

    private static boolean isBidiControl(char c) {
        return c == '‎' || c == '‏' || c == '؜' || (c >= '‪' && c <= '‮')
                || (c >= '⁦' && c <= '⁩');
    }

    /** Length as SQLite's length() counts it (Unicode code points). */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    public static Report report(Input in) {
        String now = text(in);
        Input worst = new Input(in.language(), "X".repeat(MAX_STORE_NAME), in.orderNumber(), in.date(), in.supplierName(),
                in.lines());
        int max = Math.max(length(text(worst)), length(now));
        boolean ar = "ar".equals(in.language());
        List<LineLength> lines = new ArrayList<>();
        for (int i = 0; i < in.lines().size(); i++) {
            Line l = in.lines().get(i);
            lines.add(new LineLength(l.productId(), l.productName(), length(line(ar, i + 1, l)) + 1));
        }
        List<LineLength> longest = lines.stream().sorted(Comparator.comparingInt(LineLength::length).reversed()
                .thenComparing(x -> x.productId() == null ? Long.MAX_VALUE : x.productId())).limit(5).toList();
        int remove = 0;
        if (max > MAX_LENGTH) {
            // the fewest lines to take out (longest first) so the worst case fits; recounted exactly
            List<Line> rest = new ArrayList<>(in.lines());
            rest.sort(Comparator.comparingInt((Line l) -> length(iso(l.productName())) + length(iso(l.barcode()))).reversed());
            while (!rest.isEmpty()) {
                rest.remove(0);
                remove++;
                if (length(text(new Input(in.language(), worst.storeName(), in.orderNumber(), in.date(), in.supplierName(),
                        rest))) <= MAX_LENGTH) break;
            }
        }
        return new Report(in.language(), length(now), max, MAX_LENGTH, length(now) <= MAX_LENGTH, max <= MAX_LENGTH,
                longest, remove);
    }

    /** https://wa.me/&lt;digits&gt;?text=&lt;UTF-8, percent-encoded&gt; (a space is %20, never "+"). */
    public static String url(String whatsappDigits, String text) {
        if (whatsappDigits == null || !whatsappDigits.matches("[1-9][0-9]{7,14}")) {
            throw new IllegalArgumentException("not a stored WhatsApp number");
        }
        return "https://wa.me/" + whatsappDigits + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Binds a preview to what was shown: language, exact text and number. */
    public static String previewToken(String language, String text, String whatsapp) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((language + "|" + whatsapp + "|" + text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "+" and the digits, for reading (the stored and linked form stays digits only). */
    public static String display(String digits) {
        return digits == null ? null : "+" + digits;
    }
}
