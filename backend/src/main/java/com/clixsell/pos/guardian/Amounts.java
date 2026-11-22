package com.clixsell.pos.guardian;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money and percent arithmetic for Guardian Mode: BigDecimal, half-up, like the sales code. */
public final class Amounts {

    private Amounts() {
    }

    public static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    public static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** part × 100 / whole, 4 decimals (for exact comparisons with limits). */
    public static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 4, RoundingMode.HALF_UP);
    }
}
