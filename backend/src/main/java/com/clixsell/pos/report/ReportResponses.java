package com.clixsell.pos.report;

import java.time.LocalDate;
import java.util.List;

/** JSON responses of the Reports / Sales Analytics API (all money in ₪, rounded to 0.01). */
public final class ReportResponses {

    private ReportResponses() {
    }

    /**
     * Report summary for a period.
     *
     * @param grossSales  paid totals of the completed invoices (after discount, tax included)
     * @param refunds     refunds of the returns made in the period
     * @param netSales    grossSales − refunds
     * @param previous    the same figures for the previous period (for "vs. last month")
     */
    public record Summary(LocalDate from, LocalDate to,
                          double grossSales, double discounts, double tax, double refunds, double netSales,
                          long invoiceCount, long returnCount, long unitsSold, long unitsReturned,
                          double averageInvoice,
                          Previous previous,
                          Profit profit) {
    }

    public record Previous(LocalDate from, LocalDate to, double netSales, long invoiceCount) {
    }

    /**
     * Profit needs the purchase cost of every product sold. The products table
     * has no cost price yet, so profit is reported as not available
     * (never estimated).
     */
    public record Profit(boolean available, Double amount, String reason) {
        static Profit notAvailable() {
            return new Profit(false, null, "NO_COST_PRICE");
        }
    }

    /** One day of the daily chart (days without sales are included with 0). */
    public record DayPoint(LocalDate date, double grossSales, double refunds, double netSales, long invoices) {
    }

    /** One month of the monthly chart (1–12, months without sales are 0). */
    public record MonthPoint(int month, double grossSales, double refunds, double netSales, long invoices) {
    }

    public record MonthlySales(int year, List<MonthPoint> months, double totalNetSales) {
    }

    /**
     * Best-selling products of a period.
     *
     * @param units      units sold and not returned
     * @param salesValue the product's share of what customers paid (discount and tax in proportion)
     * @param share      percent of the period's product sales value
     */
    public record TopProduct(Long productId, String name, long units, double salesValue, double share) {
    }

    public record TopProducts(LocalDate from, LocalDate to, double totalSalesValue, long totalUnits,
                              List<TopProduct> products, double othersValue, long othersUnits) {
    }

    /** Inventory report (UR-M8 / SR21) with the existing stock-status rules. */
    public record Inventory(int totalProducts, long totalUnits, double inventoryValue,
                            int inStock, int lowStock, int outOfStock,
                            List<CategoryLine> categories, List<ReorderItem> reorder) {
    }

    public record CategoryLine(String category, int products, long units, double value) {
    }

    /** A product at or below its minimum stock level (LOW_STOCK) or with no stock (OUT_OF_STOCK). */
    public record ReorderItem(long productId, String name, String barcode, String category,
                              int quantity, int minStock, String stockStatus, String supplierName) {
    }
}
