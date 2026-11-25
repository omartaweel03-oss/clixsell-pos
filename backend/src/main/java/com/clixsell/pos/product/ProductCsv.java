package com.clixsell.pos.product;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The product CSV file (import / export), RFC 4180 style, UTF-8.
 *
 * <p>Columns (one header row, any order on import): {@link #COLUMNS}. They are exactly the fields
 * of the product form (images are not part of the file). {@code costPrice} may be empty
 * (= unknown); every other column is required.
 *
 * <p>Export: UTF-8 with a byte-order mark (so spreadsheet programs read Arabic correctly), CRLF
 * line ends, fields quoted when they contain a comma, quote, line break or edge spaces. Text that
 * a spreadsheet would run as a formula (starting with {@code = + - @}, tab or carriage return)
 * is written with a leading apostrophe; the import removes exactly that apostrophe again, so an
 * exported file can be imported unchanged.
 *
 * <p>Import: strict UTF-8 (a file saved in another encoding is refused, not guessed), optional
 * byte-order mark, quoted fields with "" escapes and line breaks, blank lines ignored.
 */
public final class ProductCsv {

    public static final List<String> COLUMNS = List.of("name", "barcode", "category", "sellingPrice", "costPrice",
            "quantity", "minStock", "supplier");
    public static final List<String> OPTIONAL = List.of("costPrice");
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ProductCsv() {
    }

    // ── export ──────────────────────────────────────────────────────────────────────

    public static byte[] write(List<Product> products) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", COLUMNS)).append("\r\n");
        for (Product p : products) {
            List<String> cells = List.of(text(p.getName()), p.getBarcode(), p.getCategory(), number(p.getSellingPrice()),
                    p.getCostPrice() == null ? "" : number(p.getCostPrice()), Integer.toString(p.getQuantity()),
                    Integer.toString(p.getMinStock()), text(p.getSupplier().getName()));
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(quote(cells.get(i)));
            }
            sb.append("\r\n");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(BOM);
        out.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** A plain decimal without exponent or trailing zeros: 12, 2.5, 0.75. */
    static String number(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** Spreadsheet formula guard (OWASP "CSV injection"): text starting like a formula gets a leading apostrophe. */
    static String text(String v) {
        if (v == null || v.isEmpty()) return "";
        char c = v.charAt(0);
        return (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') ? "'" + v : v;
    }

    /** Reverses {@link #text}: only an apostrophe that guards a formula-like start is removed. */
    static String untext(String v) {
        if (v.length() >= 2 && v.charAt(0) == '\'') {
            char c = v.charAt(1);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') return v.substring(1);
        }
        return v;
    }

    static String quote(String v) {
        boolean needs = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")
                || (!v.isEmpty() && (Character.isWhitespace(v.charAt(0)) || Character.isWhitespace(v.charAt(v.length() - 1))));
        return needs ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    // ── import ──────────────────────────────────────────────────────────────────────

    /** One record of the file: the line where it starts and its fields. */
    public record Record(int line, List<String> fields, String error) {
    }

    /** The parsed file: records (header included as the first one) or a file-level error. */
    public record Parsed(List<Record> records, String fileError) {
    }

    public static Parsed parse(byte[] bytes) {
        String text;
        try {
            int start = startsWithBom(bytes) ? BOM.length : 0;
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
        } catch (CharacterCodingException e) {
            return new Parsed(List.of(), "NOT_UTF8");
        }
        List<Record> out = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean quotedField = false;
        boolean afterQuote = false;
        String recordError = null;
        int line = 1;
        int recordLine = 1;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                        afterQuote = true;
                    }
                } else {
                    if (c == '\n') line++;
                    field.append(c);
                }
                continue;
            }
            if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                quotedField = false;
                afterQuote = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                fields.add(field.toString());
                addRecord(out, recordLine, fields, recordError);
                fields = new ArrayList<>();
                field.setLength(0);
                quotedField = false;
                afterQuote = false;
                recordError = null;
                line++;
                recordLine = line;
            } else if (c == '"') {
                if (field.isEmpty() && !quotedField) {
                    inQuotes = true;
                    quotedField = true;
                } else {
                    recordError = "MALFORMED_QUOTES";
                    field.append(c);
                }
            } else {
                if (afterQuote) recordError = "MALFORMED_QUOTES"; // text after a closing quote
                field.append(c);
            }
        }
        if (inQuotes) {
            return new Parsed(out, "UNTERMINATED_QUOTE:" + recordLine);
        }
        if (!field.isEmpty() || !fields.isEmpty()) {
            fields.add(field.toString());
            addRecord(out, recordLine, fields, recordError);
        }
        return new Parsed(out, null);
    }

    private static void addRecord(List<Record> out, int line, List<String> fields, String error) {
        boolean blank = fields.stream().allMatch(f -> f.isBlank());
        if (blank && error == null) return; // empty line (or only commas): ignored
        out.add(new Record(line, List.copyOf(fields), error));
    }

    private static boolean startsWithBom(byte[] b) {
        return b.length >= 3 && b[0] == BOM[0] && b[1] == BOM[1] && b[2] == BOM[2];
    }
}
