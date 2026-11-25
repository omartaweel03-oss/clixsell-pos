package com.clixsell.pos.purchase;

import com.clixsell.pos.purchase.WhatsappMessageBuilder.Input;
import com.clixsell.pos.purchase.WhatsappMessageBuilder.Line;
import com.clixsell.pos.purchase.WhatsappMessageBuilder.Report;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The supplier message text, its length as SQLite counts it (checked against a real in-memory
 * SQLite and the exact V6 CHECK on message_text), the cleaning of names, and the wa.me link.
 */
class WhatsappMessageBuilderTest {

    private static Connection sqlite;

    @BeforeAll
    static void openSqlite() throws SQLException {
        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = sqlite.createStatement()) {
            // the exact V6 rule for purchase_orders.message_text
            s.execute("CREATE TABLE m (message_text TEXT CHECK (message_text IS NULL OR "
                    + "(typeof(message_text) = 'text' AND length(message_text) BETWEEN 1 AND 2000)))");
        }
    }

    @AfterAll
    static void closeSqlite() throws SQLException {
        sqlite.close();
    }

    private static int sqliteLength(String s) throws SQLException {
        try (PreparedStatement p = sqlite.prepareStatement("SELECT length(?)")) {
            p.setString(1, s);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    private static boolean sqliteAccepts(String text) throws SQLException {
        try (PreparedStatement p = sqlite.prepareStatement("INSERT INTO m VALUES (?)")) {
            p.setString(1, text);
            p.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getMessage().contains("CHECK")) return false;
            throw e;
        }
    }

    private static Input input(String lang, String store, List<Line> lines) {
        return new Input(lang, store, "PO-000012", LocalDate.of(2026, 11, 24), "شركة الألبان", lines);
    }

    private static final List<Line> TWO = List.of(new Line(1L, "لبنة 500غ", "7290000000017", 29),
            new Line(2L, "Milk 1L", "7290000000024", 6));

    @Test
    void arabicMessageHasEveryOrderDetailInOrderAndNothingFinancial() {
        String t = WhatsappMessageBuilder.text(input("ar", "سوبرماركت شيني", TWO));
        assertThat(t).contains("طلب شراء رقم: ⁨PO-000012⁩", "من: ⁨سوبرماركت شيني⁩",
                "إلى: ⁨شركة الألبان⁩", "التاريخ: ⁨2026-11-24⁩",
                "1) ⁨لبنة 500غ⁩ | الباركود: ⁨7290000000017⁩ | الكمية: ⁨29⁩",
                "2) ⁨Milk 1L⁩ | الباركود: ⁨7290000000024⁩ | الكمية: ⁨6⁩",
                "الرجاء تأكيد الطلب والكميات المتوفرة. شكراً.");
        assertThat(t.indexOf("1) ")).isLessThan(t.indexOf("2) "));
        assertThat(t).doesNotContain("₪", "cost", "Cost", "سعر", "تكلفة", "ربح", "profit", "total", "المجموع");
        // isolates balanced: every value opened is closed
        assertThat(t.chars().filter(c -> c == 0x2068).count()).isEqualTo(t.chars().filter(c -> c == 0x2069).count());
    }

    @Test
    void englishMessageWhenSelected() {
        String t = WhatsappMessageBuilder.text(input("en", "Shini Market", TWO));
        assertThat(t).startsWith("Purchase order: ⁨PO-000012⁩\nFrom: ⁨Shini Market⁩\n")
                .contains("1) ⁨لبنة 500غ⁩ | Barcode: ⁨7290000000017⁩ | Qty: ⁨29⁩",
                        "Please confirm this order and the available quantities. Thank you.")
                .doesNotContain("الكمية");
        assertThatThrownBy(() -> WhatsappMessageBuilder.text(input("fr", "S", TWO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void javaCountsLikeSqliteForArabicEmojiCombinedAndDirectionCharacters() throws SQLException {
        List<String> samples = List.of("abc", "لبنة", "😀", "👨‍👩‍👧", "é", "‎", "⁦x⁩",
                "line1\nline2", "🇵🇸", "حليب 😀 1L\n", "𝔘𝔫𝔦𝔠𝔬𝔡𝔢");
        for (String s : samples) {
            assertThat(WhatsappMessageBuilder.length(s)).as(s).isEqualTo(sqliteLength(s));
        }
        // String.length() would be wrong for emoji: 2 UTF-16 units, 1 SQLite character
        assertThat("😀".length()).isEqualTo(2);
        assertThat(WhatsappMessageBuilder.length("😀")).isEqualTo(1);
        // and for whole messages with such names
        String t = WhatsappMessageBuilder.text(input("ar", "متجر 😀",
                List.of(new Line(1L, "👨‍👩‍👧 عائلة é", "123", 2))));
        assertThat(WhatsappMessageBuilder.length(t)).isEqualTo(sqliteLength(t));
    }

    @Test
    void namesAreCleanedSoTheyCannotBreakLinesReorderTextOrStopSqliteCounting() {
        String dirty = "Mi‮lk\n2L\t\u0000x y\uD800z";
        String c = WhatsappMessageBuilder.clean(dirty);
        assertThat(c).isEqualTo("Milk 2L xy z".replace("xy z", "x y�z"));
        assertThat(c).doesNotContain("‮", "\n", "\t", "\u0000");
        String t = WhatsappMessageBuilder.text(input("en", "S", List.of(new Line(1L, dirty, "1\n2", 3))));
        // exactly one line per product (plus the fixed lines)
        assertThat(t.lines().filter(l -> l.startsWith("1) ")).count()).isEqualTo(1);
        assertThat(t).contains("1) ⁨Milk 2L x y�z⁩ | Barcode: ⁨1 2⁩ | Qty: ⁨3⁩");
        // emoji (a valid surrogate pair) and joiners stay
        assertThat(WhatsappMessageBuilder.clean("😀‍👩")).isEqualTo("😀‍👩");
    }

    /** One line with a name of {@code n} copies of {@code unit} (one code point each). */
    private static Input sized(String unit, int n) {
        return input("ar", "S", List.of(new Line(1L, unit.repeat(n), "1", 1)));
    }

    private static int nameFor(String unit, int target) {
        int base = WhatsappMessageBuilder.length(WhatsappMessageBuilder.text(sized(unit, 0)));
        return target - base;
    }

    @Test
    void exactly2000IsStoredAnd2001IsRefusedForArabicAndForEmoji() throws SQLException {
        for (String unit : List.of("ل", "😀", "a")) {
            int n = nameFor(unit, 2000);
            String at = WhatsappMessageBuilder.text(sized(unit, n));
            String over = WhatsappMessageBuilder.text(sized(unit, n + 1));
            assertThat(WhatsappMessageBuilder.length(at)).isEqualTo(2000);
            assertThat(WhatsappMessageBuilder.report(sized(unit, n)).fits()).isTrue();
            assertThat(WhatsappMessageBuilder.report(sized(unit, n + 1)).fits()).isFalse();
            assertThat(WhatsappMessageBuilder.report(sized(unit, n + 1)).length()).isEqualTo(2001);
            // the database agrees exactly (same rule as V6)
            assertThat(sqliteAccepts(at)).as(unit + " 2000").isTrue();
            assertThat(sqliteAccepts(over)).as(unit + " 2001").isFalse();
        }
        // with emoji, Java's String.length() is already past 2000 for the accepted text
        assertThat(WhatsappMessageBuilder.text(sized("😀", nameFor("😀", 2000))).length()).isGreaterThan(2000);
    }

    @Test
    void maxPossibleLengthCoversEveryStoreNameUpTo80() {
        Input shortName = input("ar", "S", TWO);
        Report r = WhatsappMessageBuilder.report(shortName);
        assertThat(r.maxPossibleLength() - r.length()).isEqualTo(79);
        for (String store : List.of("", "S", "سوبرماركت شيني", "😀".repeat(40), "ب".repeat(80))) {
            Input i = input("ar", store, TWO);
            assertThat(WhatsappMessageBuilder.length(WhatsappMessageBuilder.text(i))).isLessThanOrEqualTo(r.maxPossibleLength());
        }
        assertThat(r.limit()).isEqualTo(2000);
        assertThat(r.fits()).isTrue();
        assertThat(r.alwaysFits()).isTrue();
    }

    @Test
    void aLargeOrderReportsItsLongestLinesAndHowManyToRemove() {
        List<Line> lines = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            lines.add(new Line((long) i, (i % 7 == 0 ? "Long product name " : "Item ") + "x".repeat(i % 7 == 0 ? 60 : 10) + i,
                    "72900000" + (10000 + i), i));
        }
        Input in = input("en", "Shop", lines);
        Report r = WhatsappMessageBuilder.report(in);
        assertThat(r.fits()).isFalse();
        assertThat(r.alwaysFits()).isFalse();
        assertThat(r.longestLines()).hasSize(5);
        assertThat(r.longestLines().get(0).productName()).startsWith("Long product name");
        assertThat(r.longestLines().get(0).length()).isGreaterThanOrEqualTo(r.longestLines().get(4).length());
        assertThat(r.minLinesToRemove()).isPositive();
        // removing that many longest lines makes the worst case fit; one fewer does not
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort((a, b) -> Integer.compare(b.productName().length() + b.barcode().length(),
                a.productName().length() + a.barcode().length()));
        Input fewer = input("en", "Shop", sorted.subList(r.minLinesToRemove(), sorted.size()));
        Input oneMore = input("en", "Shop", sorted.subList(r.minLinesToRemove() - 1, sorted.size()));
        assertThat(WhatsappMessageBuilder.report(fewer).alwaysFits()).isTrue();
        assertThat(WhatsappMessageBuilder.report(oneMore).alwaysFits()).isFalse();
    }

    @Test
    void theLinkCarriesTheExactTextAndTheStoredNumber() {
        String text = WhatsappMessageBuilder.text(input("ar", "A & B #1 ?", List.of(
                new Line(1L, "50% off / + plus 😀", "12&34", 7))));
        String url = WhatsappMessageBuilder.url("970599123456", text);
        assertThat(url).startsWith("https://wa.me/970599123456?text=");
        String encoded = url.substring(url.indexOf("?text=") + 6);
        assertThat(encoded).doesNotContain("+", " ", "&", "#", "\n");
        assertThat(URLDecoder.decode(encoded, StandardCharsets.UTF_8)).isEqualTo(text);
        // an Arabic letter takes 6 characters in the link, an emoji 12
        assertThat(WhatsappMessageBuilder.url("970599123456", "ل")).endsWith("text=%D9%84");
        assertThat(WhatsappMessageBuilder.url("970599123456", "😀")).endsWith("text=%F0%9F%98%80");
        for (String bad : new String[]{null, "", "0599123456", "+970599123456", "1234567", "1234567890123456"}) {
            assertThatThrownBy(() -> WhatsappMessageBuilder.url(bad, text)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void thePreviewTokenChangesWithLanguageTextOrNumber() {
        String t = "x";
        String base = WhatsappMessageBuilder.previewToken("ar", t, "970599123456");
        assertThat(base).hasSize(64).matches("[0-9a-f]+");
        assertThat(WhatsappMessageBuilder.previewToken("ar", t, "970599123456")).isEqualTo(base);
        assertThat(WhatsappMessageBuilder.previewToken("en", t, "970599123456")).isNotEqualTo(base);
        assertThat(WhatsappMessageBuilder.previewToken("ar", "y", "970599123456")).isNotEqualTo(base);
        assertThat(WhatsappMessageBuilder.previewToken("ar", t, "970599123457")).isNotEqualTo(base);
    }
}
