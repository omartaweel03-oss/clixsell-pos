package com.clixsell.pos.guardian;

import java.math.BigDecimal;

/**
 * Every limit Guardian Mode uses (final plan, part 4). Fixed in Phase 1 and
 * shown inside each alert's explanation, so the manager always sees why an
 * alert appeared. (Editable limits are Phase 2.)
 */
public final class GuardianThresholds {

    private GuardianThresholds() {
    }

    // G1 – high discount
    /** A discount counts as high from 75% of the store's maximum discount. */
    public static final BigDecimal HIGH_DISCOUNT_SHARE_OF_MAX = new BigDecimal("0.75");
    /** ... and only from this amount (₪), so tiny invoices are not reported. */
    public static final BigDecimal HIGH_DISCOUNT_MIN_AMOUNT = new BigDecimal("5.00");
    /** The same (logged) user with this many high discounts in one day → one HIGH alert. */
    public static final int HIGH_DISCOUNT_REPEAT = 3;

    // G2 – profit
    /** Smallest loss that counts (after rounding to 2 decimals). */
    public static final BigDecimal MIN_LOSS = new BigDecimal("0.01");

    // G6 – stockout soon
    public static final int STOCKOUT_WINDOW_DAYS = 14;
    public static final int STOCKOUT_MIN_OBSERVED_DAYS = 7;
    public static final int STOCKOUT_MIN_NET_UNITS = 3;
    public static final double STOCKOUT_HIGH_DAYS = 3;
    public static final double STOCKOUT_MEDIUM_DAYS = 7;

    // G7 – canceled invoices (per day): count AND share of the day's invoices
    public static final int CANCEL_MEDIUM_COUNT = 3;
    public static final int CANCEL_MEDIUM_PERCENT = 10;
    public static final int CANCEL_HIGH_COUNT = 6;
    public static final int CANCEL_HIGH_PERCENT = 20;

    // G8 – returns
    public static final BigDecimal RETURNS_MIN_AMOUNT = new BigDecimal("50.00");
    public static final int RETURNS_MEDIUM_PERCENT = 10;
    public static final int RETURNS_HIGH_PERCENT = 25;
    public static final int PRODUCT_RETURNS_REPEAT = 3;
    public static final int PRODUCT_RETURNS_DAYS = 7;

    // G9 – product deletions (same user, one day)
    public static final int DELETIONS_MEDIUM = 5;
    public static final int DELETIONS_HIGH = 10;

    // T2 / T3
    public static final int LOW_MARGIN_PERCENT = 10;
    public static final int SLOW_MOVING_DAYS = 30;

    // Store Health Score
    public static final int HEALTH_MIN_INVOICES = 5;
    public static final int HEALTH_MIN_COST_COVERAGE_PERCENT = 50;
    public static final int HEALTH_MIN_PARTS = 3;
    public static final int HEALTH_GOOD = 80;
    public static final int HEALTH_ATTENTION = 60;
}
