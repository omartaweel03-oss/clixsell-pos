package com.clixsell.pos.guardian;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the Guardian rules look at, read from ONE store's real records
 * (see {@link GuardianRepository}). The rules are plain calculations on this
 * data, so each one can be tested with exact numbers.
 *
 * @param now               current server time
 * @param from              first day of the chosen period
 * @param to                last day of the chosen period (today for the current month)
 * @param currentPeriod     true when the period is the current month (current stock applies)
 * @param maxDiscountPercent the store's maximum discount now
 * @param trackedSince      when the activity log started for this store
 * @param products          the store's products now
 * @param invoices          completed invoices of the period
 * @param belowCostLines    sold lines of the period with a known sale-time cost and a price below it
 * @param cancels           invoices canceled in the period
 * @param returns           returns of the period
 * @param discountLogs      DISCOUNT_APPLIED log rows of the period's invoices, by sale id
 * @param cancelLogs        INVOICE_CANCELED log rows of the period's canceled invoices, by sale id
 * @param deletions         PRODUCT_DELETED log rows of the period
 * @param netUnitsLast14    product id → units sold minus units returned in the last 14 days (today included)
 * @param soldLast30        ids of products with at least one unit sold in the last 30 days
 * @param productReturns    returns from 14 days before the period to its end (G8b: a 7-day window
 *                          may start in the previous month; only windows reaching the limit inside
 *                          the period are reported)
 * @param discountLines     sold lines of the period's DISCOUNTED invoices that have a sale-time cost (G2c)
 */
public record GuardianData(
        LocalDateTime now,
        LocalDate from,
        LocalDate to,
        boolean currentPeriod,
        double maxDiscountPercent,
        LocalDateTime trackedSince,
        List<ProductFact> products,
        List<InvoiceFact> invoices,
        List<LineFact> belowCostLines,
        List<CancelFact> cancels,
        List<ReturnFact> returns,
        Map<Long, LogFact> discountLogs,
        Map<Long, LogFact> cancelLogs,
        List<LogFact> deletions,
        Map<Long, Long> netUnitsLast14,
        Set<Long> soldLast30,
        List<ReturnFact> productReturns,
        List<ShareLineFact> discountLines) {

    /** Without the extra G8b / G2c lists (they default to the period's returns / none). */
    public GuardianData(LocalDateTime now, LocalDate from, LocalDate to, boolean currentPeriod,
                        double maxDiscountPercent, LocalDateTime trackedSince, List<ProductFact> products,
                        List<InvoiceFact> invoices, List<LineFact> belowCostLines, List<CancelFact> cancels,
                        List<ReturnFact> returns, Map<Long, LogFact> discountLogs, Map<Long, LogFact> cancelLogs,
                        List<LogFact> deletions, Map<Long, Long> netUnitsLast14, Set<Long> soldLast30) {
        this(now, from, to, currentPeriod, maxDiscountPercent, trackedSince, products, invoices, belowCostLines,
                cancels, returns, discountLogs, cancelLogs, deletions, netUnitsLast14, soldLast30, returns, List.of());
    }

    public LocalDate today() {
        return now.toLocalDate();
    }

    public Optional<ProductFact> product(Long id) {
        if (id == null) return Optional.empty();
        return products.stream().filter(p -> p.id() == id).findFirst();
    }

    /** A product now (cost is null when not recorded). */
    public record ProductFact(long id, String name, String category, double price, Double cost,
                              int quantity, int minStock, LocalDateTime createdAt) {

        public boolean outOfStock() {
            return quantity <= 0;
        }

        public boolean lowStock() {
            return quantity > 0 && quantity <= minStock;
        }

        public boolean healthyStock() {
            return !outOfStock() && !lowStock();
        }
    }

    /**
     * A completed invoice AS SOLD. {@code cost} is the sum of unit_cost × quantity of
     * the lines that HAVE a sale-time cost; {@code linesWithoutCost} counts the others.
     * {@code unitsReturned} (returned later) never changes the sale-time figures; it is
     * only shown so a sale-time loss is not read as a loss that still exists.
     */
    public record InvoiceFact(long saleId, String invoiceNumber, long startedByUserId, String startedByName,
                              double subtotal,
                              double discount, double total, LocalDateTime completedAt,
                              int lines, int linesWithoutCost, double cost, int unitsSold, int unitsReturned,
                              String startedByRole) {

        public InvoiceFact(long saleId, String invoiceNumber, long startedByUserId, String startedByName,
                           double subtotal, double discount, double total, LocalDateTime completedAt, int lines,
                           int linesWithoutCost, double cost, int unitsSold, int unitsReturned) {
            this(saleId, invoiceNumber, startedByUserId, startedByName, subtotal, discount, total, completedAt, lines,
                    linesWithoutCost, cost, unitsSold, unitsReturned, null);
        }
    }

    /**
     * A sold line of a discounted invoice, with a sale-time cost (G2c). Its share of
     * the invoice discount uses the Reports profit rule:
     * line_total × (subtotal − discount) ÷ subtotal.
     */
    public record ShareLineFact(long saleId, String invoiceNumber, Long productId, String productName, int quantity,
                                double unitPrice, double unitCost, double lineTotal, double subtotal, double discount,
                                LocalDateTime completedAt, int returnedQuantity) {
    }

    /** A sold line; {@code returnedQuantity} = units of it returned later. */
    public record LineFact(long saleId, String invoiceNumber, Long productId, String productName,
                           int quantity, double unitPrice, double unitCost, LocalDateTime completedAt,
                           int returnedQuantity) {
    }

    public record CancelFact(long saleId, String invoiceNumber, double total, long startedByUserId,
                             String startedByName, LocalDateTime canceledAt) {
    }

    /** A return. The user is the one who processed it (recorded by the backend from the session). */
    public record ReturnFact(long returnId, long saleId, Long productId, String productName, int quantity,
                             double refund, LocalDateTime returnDate, long userId, String userName, String userRole) {

        public ReturnFact(long returnId, long saleId, Long productId, String productName, int quantity, double refund,
                          LocalDateTime returnDate, long userId, String userName) {
            this(returnId, saleId, productId, productName, quantity, refund, returnDate, userId, userName, null);
        }
    }

    public record LogFact(long logId, String type, long userId, String userName, Long entityId,
                          String entityName, Map<String, Object> details, LocalDateTime createdAt, String userRole) {

        public LogFact(long logId, String type, long userId, String userName, Long entityId, String entityName,
                       Map<String, Object> details, LocalDateTime createdAt) {
            this(logId, type, userId, userName, entityId, entityName, details, createdAt, null);
        }

        public Double number(String key) {
            Object v = details == null ? null : details.get(key);
            return v instanceof Number n ? n.doubleValue() : null;
        }
    }
}
