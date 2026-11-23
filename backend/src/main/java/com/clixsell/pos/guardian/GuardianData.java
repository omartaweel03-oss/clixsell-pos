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
        Set<Long> soldLast30) {

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
                              int lines, int linesWithoutCost, double cost, int unitsSold, int unitsReturned) {
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
                             double refund, LocalDateTime returnDate, long userId, String userName) {
    }

    public record LogFact(long logId, String type, long userId, String userName, Long entityId,
                          String entityName, Map<String, Object> details, LocalDateTime createdAt) {

        public Double number(String key) {
            Object v = details == null ? null : details.get(key);
            return v instanceof Number n ? n.doubleValue() : null;
        }
    }
}
