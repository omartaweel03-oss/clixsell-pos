package com.clixsell.pos.product;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The barcode values to look up for one scanned code, in order: the exact value first, then only the
 * representations that GS1 defines as the SAME product number (GTIN), each with a valid check digit:
 * <ul>
 *   <li>UPC-A (12 digits) and the same number as EAN-13 with a leading 0 (13 digits), both ways;</li>
 *   <li>UPC-E (8 digits, number system 0 or 1), expanded to its UPC-A / EAN-13 form — only when the
 *       scanner says the symbol WAS UPC-E (an EAN-8 is a different number and is never expanded).</li>
 * </ul>
 * Nothing else is changed: no trimming of other digits, no guessing. A value that cannot be a ClixSell
 * product barcode (products use 8–14 digits) gives no candidates.
 */
public final class BarcodeForms {

    /** Symbologies the camera scanner may report. */
    public static final Set<String> FORMATS = Set.of("EAN_13", "EAN_8", "UPC_A", "UPC_E", "CODE_128");

    private BarcodeForms() {
    }

    /** True when the value can be a ClixSell product barcode (same rule as Product.validate). */
    public static boolean isProductBarcode(String code) {
        return code != null && code.matches("\\d{8,14}");
    }

    public static List<String> candidates(String code, String format) {
        List<String> out = new ArrayList<>();
        if (!isProductBarcode(code)) return out;
        out.add(code);
        String f = format == null ? "" : format.toUpperCase(Locale.ROOT);
        if (code.length() == 12 && validGtin(code)) {
            out.add("0" + code);
        } else if (code.length() == 13 && code.charAt(0) == '0' && validGtin(code)) {
            out.add(code.substring(1));
        } else if (code.length() == 8 && f.equals("UPC_E")) {
            String upcA = expandUpcE(code);
            if (upcA != null) {
                out.add(upcA);
                out.add("0" + upcA);
            }
        }
        return out;
    }

    /** GS1 mod-10 check digit over all digits (the last digit is the check digit). */
    static boolean validGtin(String digits) {
        int sum = 0;
        int n = digits.length();
        for (int i = n - 2, weight = 3; i >= 0; i--, weight = weight == 3 ? 1 : 3) {
            sum += (digits.charAt(i) - '0') * weight;
        }
        int check = (10 - sum % 10) % 10;
        return check == digits.charAt(n - 1) - '0';
    }

    /** GS1 UPC-E to UPC-A expansion; null when the value is not a valid UPC-E. */
    static String expandUpcE(String upcE) {
        if (upcE == null || !upcE.matches("[01]\\d{7}")) return null;
        char ns = upcE.charAt(0);
        String d = upcE.substring(1, 7);
        char check = upcE.charAt(7);
        char last = d.charAt(5);
        String body = switch (last) {
            case '0', '1', '2' -> "" + d.charAt(0) + d.charAt(1) + last + "0000" + d.charAt(2) + d.charAt(3) + d.charAt(4);
            case '3' -> d.substring(0, 3) + "00000" + d.charAt(3) + d.charAt(4);
            case '4' -> d.substring(0, 4) + "00000" + d.charAt(4);
            default -> d.substring(0, 5) + "0000" + last;
        };
        String upcA = ns + body + check;
        return validGtin(upcA) ? upcA : null;
    }
}
