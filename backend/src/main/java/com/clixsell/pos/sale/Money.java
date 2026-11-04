package com.clixsell.pos.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money arithmetic: calculated with BigDecimal, rounded half-up to 2 decimals (agorot). */
final class Money {

    private Money() {
    }

    static BigDecimal of(double value) {
        return BigDecimal.valueOf(value);
    }

    static double round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
