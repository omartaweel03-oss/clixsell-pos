package com.clixsell.pos.guardian;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * JSON sent to the app by /api/guardian/**. The server sends codes and numbers
 * (never ready-made sentences); the app writes the Arabic / English text.
 * Only names and numbers of the logged-in user's own store are included.
 */
public final class GuardianResponses {

    private GuardianResponses() {
    }

    public enum Severity {
        HIGH, MEDIUM, LOW;

        public int weight() {
            return switch (this) {
                case HIGH -> 3;
                case MEDIUM -> 2;
                case LOW -> 1;
            };
        }
    }

    /**
     * One alert.
     *
     * @param key        unique per rule and subject (no duplicates)
     * @param rule       G1 … G9 (G2a / G2b, G8a / G8b)
     * @param code       what happened, e.g. HIGH_DISCOUNT
     * @param params     the numbers and limits behind the alert
     * @param evidence   the records it is based on (invoices, products, log rows)
     * @param actor      the user who did it, when the backend recorded it; else null
     * @param actorNote  NOT_RECORDED when the user is unknown (before the activity log started)
     * @param occurredAt when it happened; null for a "current state" alert (stock, prices)
     * @param day        the day it belongs to; null for a current-state alert
     * @param action     the screen that can be opened for it, or null
     */
    public record Alert(String key, String rule, String code, Severity severity, String category,
                        Map<String, Object> params, List<Ref> evidence, Actor actor, String actorNote,
                        LocalDateTime occurredAt, LocalDate day, Action action) {
    }

    /** A record an alert is based on: SALE, PRODUCT or LOG. */
    public record Ref(String type, Long id, String label) {
    }

    /** @param source ACTIVITY_LOG or RETURN_RECORD (where the backend recorded the user) */
    public record Actor(long userId, String name, String source) {
    }

    /** @param type INVOICE, PRODUCT, STOCK_ALERTS or ACTIVITY */
    public record Action(String type, Long id) {
    }

    public record Status(boolean enabled, LocalDateTime changedAt, String changedBy,
                         LocalDateTime activityTrackedSince) {
    }

    public record TodaySales(double today, double yesterdaySameTime, Double changePercent,
                             List<DaySales> last7Days) {
    }

    public record DaySales(LocalDate date, double netSales) {
    }

    /** Invoices whose profit is known / not known (missing sale-time cost), and products without a cost. */
    public record CostData(long invoices, long completeCostInvoices, long incompleteCostInvoices,
                           long confirmedLossInvoices, long productsWithoutCost) {
    }

    /**
     * One part of the Store Health Score.
     *
     * @param points null when the part is not available
     * @param reason why it is not available (null when it is)
     */
    public record HealthPart(String part, boolean available, Double points, String reason,
                             Map<String, Object> numbers) {
    }

    /** @param score null with reason INSUFFICIENT_DATA when fewer than 3 parts are available */
    public record Health(Integer score, String band, int partsUsed, String reason, List<HealthPart> parts) {
    }

    /** @param kind CATEGORY (stock health of a category, now) or CASHIER_AREA (POS activity of the period) */
    public record MapTile(String kind, String category, Integer percent, String band, int products,
                          int healthyProducts, int highAlerts, int mediumAlerts) {
    }

    /** @param prediction OK, INSUFFICIENT_DATA or OUT_OF_STOCK */
    public record AtRiskProduct(long productId, String name, int quantity, int minStock, LocalDate expectedStockout,
                                Double daysLeft, Double unitsPerDay, String prediction, Severity risk) {
    }

    /** @param status PRICE_BELOW_COST, PRICE_EQUALS_COST or MAX_DISCOUNT_RISK */
    public record ProfitProtection(long productId, String name, double cost, double price, double maxDiscountPercent,
                                   double priceAfterMaxDiscount, double safeDiscountPercent, String status) {
    }

    /** @param code REORDER_SOON (T1), LOW_MARGIN (T2) or SLOW_MOVING (T3) */
    public record Recommendation(String rule, String code, int count, List<Map<String, Object>> products,
                                 Action action) {
    }

    /** Cases of the period that are part of an alert, by type (donut "Unusual activity"). */
    public record ActivitySummary(long highDiscounts, long cancellations, long returns, long deletions, long total,
                                  int days, double perDay, LocalDateTime trackedSince) {
    }

    public record Dashboard(boolean enabled, Status status, LocalDate from, LocalDate to, boolean currentPeriod,
                            TodaySales todaySales, int criticalCount, int profitRiskCount, CostData costData,
                            Health health, List<Alert> topAlerts, int alertCount, List<Recommendation> recommendations,
                            List<MapTile> storeMap, List<AtRiskProduct> atRiskProducts,
                            List<ProfitProtection> profitProtection, ActivitySummary activity) {

        static Dashboard disabled(Status status) {
            return new Dashboard(false, status, null, null, false, null, 0, 0, null, null, List.of(), 0, List.of(),
                    List.of(), List.of(), List.of(), null);
        }
    }

    public record AlertList(boolean enabled, LocalDate from, LocalDate to, List<Alert> alerts) {
    }

    public record ActivityEntry(long logId, String type, LocalDateTime createdAt, long userId, String userName,
                                String entityType, Long entityId, String entityName, Map<String, Object> details) {
    }

    public record ActivityPage(boolean enabled, LocalDate from, LocalDate to, int page, int pageSize, long total,
                               LocalDateTime trackedSince, List<ActivityEntry> entries) {
    }
}
