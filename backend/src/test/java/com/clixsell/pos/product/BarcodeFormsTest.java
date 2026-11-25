package com.clixsell.pos.product;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** GS1 rules only: the same product number in its other standard form, never a different number. */
class BarcodeFormsTest {

    @Test
    void checkDigitsOfPublishedExamples() {
        assertThat(BarcodeForms.validGtin("4006381333931")).isTrue();  // EAN-13
        assertThat(BarcodeForms.validGtin("036000291452")).isTrue();   // UPC-A
        assertThat(BarcodeForms.validGtin("96385074")).isTrue();       // EAN-8
        assertThat(BarcodeForms.validGtin("4006381333932")).isFalse();
    }

    @Test
    void exactValueComesFirstAndUpcAEan13AreTheSameNumber() {
        assertThat(BarcodeForms.candidates("036000291452", "UPC_A")).containsExactly("036000291452", "0036000291452");
        assertThat(BarcodeForms.candidates("0036000291452", "EAN_13")).containsExactly("0036000291452", "036000291452");
        assertThat(BarcodeForms.candidates("4006381333931", "EAN_13")).containsExactly("4006381333931");
    }

    @Test
    void upcEIsExpandedOnlyWhenTheSymbolWasUpcE() {
        assertThat(BarcodeForms.expandUpcE("04252614")).isEqualTo("042100005264");
        assertThat(BarcodeForms.candidates("04252614", "UPC_E"))
                .containsExactly("04252614", "042100005264", "0042100005264");
        // the same 8 digits read as EAN-8 are a different number: never expanded
        assertThat(BarcodeForms.candidates("04252614", "EAN_8")).containsExactly("04252614");
        assertThat(BarcodeForms.candidates("96385074", null)).containsExactly("96385074");
    }

    @Test
    void invalidCheckDigitsGiveNoEquivalentAndNonProductValuesGiveNothing() {
        assertThat(BarcodeForms.candidates("036000291453", "UPC_A")).containsExactly("036000291453");
        assertThat(BarcodeForms.candidates("ABC-123", "CODE_128")).isEmpty();
        assertThat(BarcodeForms.candidates("1234567", null)).isEmpty();        // 7 digits
        assertThat(BarcodeForms.candidates("123456789012345", null)).isEmpty(); // 15 digits
        assertThat(BarcodeForms.candidates(null, null)).isEmpty();
    }
}
