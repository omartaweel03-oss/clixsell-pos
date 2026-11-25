package com.clixsell.pos.product;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The CSV format of product files: quoting, line breaks, BOM, UTF-8 and the formula guard. */
class ProductCsvTest {

    private static List<ProductCsv.Record> parse(String s) {
        ProductCsv.Parsed p = ProductCsv.parse(s.getBytes(StandardCharsets.UTF_8));
        assertThat(p.fileError()).isNull();
        return p.records();
    }

    @Test
    void quotedFieldsWithCommasQuotesAndLineBreaksAreRead() {
        List<ProductCsv.Record> r = parse("name,barcode\r\n\"Milk, 1L \"\"Fresh\"\"\",12345678\r\n\"Two\nlines\",23456789\n");
        assertThat(r).hasSize(3);
        assertThat(r.get(1).fields()).containsExactly("Milk, 1L \"Fresh\"", "12345678");
        assertThat(r.get(2).fields()).containsExactly("Two\nlines", "23456789");
        assertThat(r.get(2).line()).isEqualTo(3);
        assertThat(r.get(1).error()).isNull();
    }

    @Test
    void aByteOrderMarkIsIgnoredAndArabicIsKept() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "name,barcode\nلبنة 500غ,12345678\n".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, 3);
        System.arraycopy(body, 0, all, 3, body.length);
        ProductCsv.Parsed p = ProductCsv.parse(all);
        assertThat(p.records().get(0).fields()).containsExactly("name", "barcode");
        assertThat(p.records().get(1).fields().get(0)).isEqualTo("لبنة 500غ");
    }

    @Test
    void notUtf8IsRefusedNotGuessed() {
        byte[] cp1256 = "name\nلبنة\n".getBytes(Charset.forName("windows-1256"));
        assertThat(ProductCsv.parse(cp1256).fileError()).isEqualTo("NOT_UTF8");
    }

    @Test
    void anUnterminatedQuoteIsAFileErrorAndStrayQuotesAreRowErrors() {
        assertThat(ProductCsv.parse("a,b\n\"open,1\n2,3\n".getBytes(StandardCharsets.UTF_8)).fileError())
                .isEqualTo("UNTERMINATED_QUOTE:2");
        List<ProductCsv.Record> r = parse("a,b\nab\"c,1\n\"x\"y,2\n");
        assertThat(r.get(1).error()).isEqualTo("MALFORMED_QUOTES");
        assertThat(r.get(2).error()).isEqualTo("MALFORMED_QUOTES");
    }

    @Test
    void blankLinesAreIgnored() {
        assertThat(parse("a,b\n\n,\n1,2\n\n")).hasSize(2);
    }

    @Test
    void formulaLikeTextIsGuardedOnExportAndRestoredOnImport() {
        for (String danger : new String[]{"=SUM(A1)", "+1", "-2", "@cmd", "\tx"}) {
            String guarded = ProductCsv.text(danger);
            assertThat(guarded).startsWith("'");
            assertThat(ProductCsv.untext(guarded)).isEqualTo(danger);
        }
        assertThat(ProductCsv.text("Milk")).isEqualTo("Milk");
        assertThat(ProductCsv.untext("'quoted name")).as("a normal leading apostrophe is kept").isEqualTo("'quoted name");
    }

    @Test
    void numbersAreWrittenPlainWithoutExponentOrTrailingZeros() {
        assertThat(ProductCsv.number(12.0)).isEqualTo("12");
        assertThat(ProductCsv.number(2.5)).isEqualTo("2.5");
        assertThat(ProductCsv.number(0.75)).isEqualTo("0.75");
        assertThat(ProductCsv.number(1_000_000.0)).isEqualTo("1000000");
        assertThat(ProductCsv.number(9999.99)).isEqualTo("9999.99");
    }

    @Test
    void quotingOnlyWhenNeeded() {
        assertThat(ProductCsv.quote("plain")).isEqualTo("plain");
        assertThat(ProductCsv.quote("a,b")).isEqualTo("\"a,b\"");
        assertThat(ProductCsv.quote("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ProductCsv.quote(" edge ")).isEqualTo("\" edge \"");
        assertThat(ProductCsv.quote("x\ny")).isEqualTo("\"x\ny\"");
    }
}
