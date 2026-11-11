package com.clixsell.pos.report;

import com.clixsell.pos.product.Product;
import com.clixsell.pos.product.ProductService;
import com.clixsell.pos.product.StockStatus;
import com.clixsell.pos.report.ReportRepository.ProductSales;
import com.clixsell.pos.report.ReportRepository.RefundTotals;
import com.clixsell.pos.report.ReportRepository.SalesTotals;
import com.clixsell.pos.report.ReportResponses.CategoryLine;
import com.clixsell.pos.report.ReportResponses.DayPoint;
import com.clixsell.pos.report.ReportResponses.Inventory;
import com.clixsell.pos.report.ReportResponses.MonthPoint;
import com.clixsell.pos.report.ReportResponses.MonthlySales;
import com.clixsell.pos.report.ReportResponses.Previous;
import com.clixsell.pos.report.ReportResponses.Profit;
import com.clixsell.pos.report.ReportResponses.ReorderItem;
import com.clixsell.pos.report.ReportResponses.Summary;
import com.clixsell.pos.report.ReportResponses.TopProduct;
import com.clixsell.pos.report.ReportResponses.TopProducts;
import com.clixsell.pos.common.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reports and Sales Analytics business logic (report Figures 3.7 and 3.8,
 * UR-M7/M8, SR20/SR21). Read-only; every figure is calculated here from the
 * store's own records – the app only displays it.
 *
 * <p>Rules:
 * <ul>
 *   <li>Only COMPLETED sales count, dated by their completion time.</li>
 *   <li>Sales amounts are what customers paid: after the invoice discount, tax included.</li>
 *   <li>Returns are subtracted on the day the refund was given (net = sales − refunds).</li>
 *   <li>Product performance = units sold minus units returned, valued with the same
 *       proportional rule used for refunds.</li>
 *   <li>Low / out of stock use the existing product rules (quantity ≤ minimum stock / = 0).</li>
 *   <li>Profit = revenue without tax (after discount) − the purchase cost saved on
 *       each line at sale time; returns subtract their revenue and cost on the return
 *       date. Lines without a saved cost are never guessed: profit is then PARTIAL
 *       (lines with a cost only) or NOT_AVAILABLE.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    static final int DEFAULT_TOP = 7;
    static final int MAX_TOP = 50;

    private final ReportRepository reports;
    private final ProductService products;

    public ReportService(ReportRepository reports, ProductService products) {
        this.reports = reports;
        this.products = products;
    }

    public Summary summary(long storeId, ReportPeriod period) {
        SalesTotals sales = reports.salesTotals(storeId, period);
        RefundTotals refunds = reports.refundTotals(storeId, period);
        long unitsSold = reports.unitsSold(storeId, period);
        Profit profit = profit(reports.profitParts(storeId, period));

        ReportPeriod prev = period.previous();
        SalesTotals prevSales = reports.salesTotals(storeId, prev);
        RefundTotals prevRefunds = reports.refundTotals(storeId, prev);
        Profit prevProfit = profit(reports.profitParts(storeId, prev));
        return new Summary(period.from(), period.to(),
                round(sales.total()), round(sales.discount()), round(sales.tax()),
                round(refunds.refunds()), round(sales.total() - refunds.refunds()),
                sales.invoices(), refunds.returns(),
                unitsSold, refunds.units(), unitsSold - refunds.units(),
                sales.invoices() == 0 ? 0 : round(sales.total() / sales.invoices()),
                new Previous(prev.from(), prev.to(),
                        round(prevSales.total() - prevRefunds.refunds()), prevSales.invoices(),
                        reports.unitsSold(storeId, prev) - prevRefunds.units(),
                        profit.complete() && prevProfit.complete() ? prevProfit.amount() : null),
                profit);
    }

    /** Turns the period's revenue / cost parts into a profit that never pretends to be complete. */
    static Profit profit(ReportRepository.ProfitParts p) {
        double revenue = round(p.revenue());
        double covered = round(p.coveredRevenue());
        double cost = round(p.cost());
        if (p.linesWithoutCost() == 0) {
            // Every line has a cost (or there was nothing to sell / return): exact profit.
            return new Profit("COMPLETE", true, true, round(p.coveredRevenue() - p.cost()), revenue, covered, cost,
                    100.0, 0, null);
        }
        if (p.linesWithoutCost() == p.lines()) {
            return new Profit("NOT_AVAILABLE", false, false, null, revenue, 0, 0, 0.0, p.linesWithoutCost(),
                    "NO_COST_PRICE");
        }
        Double coverage = p.revenue() > 0 ? round(Math.max(0, Math.min(100, p.coveredRevenue() * 100 / p.revenue())))
                : null;
        return new Profit("PARTIAL", true, false, round(p.coveredRevenue() - p.cost()), revenue, covered, cost,
                coverage, p.linesWithoutCost(), "MISSING_COST");
    }

    public List<DayPoint> dailySales(long storeId, ReportPeriod period) {
        Map<String, double[]> byDay = reports.grouped(storeId, period, 10);
        List<DayPoint> days = new ArrayList<>();
        for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
            double[] v = byDay.getOrDefault(d.toString(), new double[3]);
            days.add(new DayPoint(d, round(v[0]), round(v[1]), round(v[0] - v[1]), (long) v[2]));
        }
        return days;
    }

    public MonthlySales monthlySales(long storeId, Integer year) {
        ReportPeriod period = ReportPeriod.ofYear(year);
        Map<String, double[]> byMonth = reports.grouped(storeId, period, 7);
        List<MonthPoint> months = new ArrayList<>();
        double total = 0;
        for (int m = 1; m <= 12; m++) {
            double[] v = byMonth.getOrDefault(String.format("%04d-%02d", year, m), new double[3]);
            months.add(new MonthPoint(m, round(v[0]), round(v[1]), round(v[0] - v[1]), (long) v[2]));
            total += v[0] - v[1];
        }
        return new MonthlySales(year, months, round(total));
    }

    public TopProducts topProducts(long storeId, ReportPeriod period, Integer limit) {
        int n = limit == null ? DEFAULT_TOP : limit;
        if (n < 1 || n > MAX_TOP) {
            throw new ValidationException(Map.of("limit", "Limit must be between 1 and " + MAX_TOP));
        }
        List<ProductSales> rows = reports.productSales(storeId, period);
        // Totals include returns of earlier sales (so they match Net Revenue / Net Units);
        // the list shows products with units still sold in the period.
        double totalValue = rows.stream().mapToDouble(ProductSales::value).sum();
        long totalUnits = rows.stream().mapToLong(ProductSales::units).sum();
        List<ProductSales> all = rows.stream().filter(r -> r.units() > 0 && r.value() > 0).toList();
        List<TopProduct> top = new ArrayList<>();
        double topValue = 0;
        long topUnits = 0;
        for (ProductSales p : all.subList(0, Math.min(n, all.size()))) {
            top.add(new TopProduct(p.productId(), p.name(), p.units(), round(p.value()),
                    totalValue <= 0 ? 0 : round(p.value() * 100 / totalValue)));
            topValue += p.value();
            topUnits += p.units();
        }
        return new TopProducts(period.from(), period.to(), round(totalValue), totalUnits, top,
                round(totalValue - topValue), totalUnits - topUnits);
    }

    public Inventory inventory(long storeId) {
        List<Product> list = products.listProducts(storeId);
        int in = 0, low = 0, out = 0;
        long units = 0;
        BigDecimal value = BigDecimal.ZERO;
        Map<String, Object[]> cats = new TreeMap<>();
        List<Product> reorder = new ArrayList<>();
        for (Product p : list) {
            StockStatus status = p.getStockStatus();
            switch (status) {
                case IN_STOCK -> in++;
                case LOW_STOCK -> low++;
                case OUT_OF_STOCK -> out++;
            }
            if (status != StockStatus.IN_STOCK) reorder.add(p);
            BigDecimal v = BigDecimal.valueOf(p.getSellingPrice()).multiply(BigDecimal.valueOf(p.getQuantity()));
            units += p.getQuantity();
            value = value.add(v);
            Object[] c = cats.computeIfAbsent(p.getCategory(), k -> new Object[]{0, 0L, BigDecimal.ZERO});
            c[0] = (int) c[0] + 1;
            c[1] = (long) c[1] + p.getQuantity();
            c[2] = ((BigDecimal) c[2]).add(v);
        }
        List<CategoryLine> categories = new ArrayList<>();
        cats.forEach((k, c) -> categories.add(
                new CategoryLine(k, (int) c[0], (long) c[1], round(((BigDecimal) c[2]).doubleValue()))));
        // Out of stock first, then the lowest stock compared with the minimum.
        reorder.sort(Comparator
                .comparing((Product p) -> p.getStockStatus() != StockStatus.OUT_OF_STOCK)
                .thenComparingDouble(p -> p.getMinStock() == 0 ? 0 : (double) p.getQuantity() / p.getMinStock())
                .thenComparing(Product::getName));
        List<ReorderItem> reorderItems = reorder.stream().map(p -> new ReorderItem(
                p.getProductId(), p.getName(), p.getBarcode(), p.getCategory(), p.getQuantity(), p.getMinStock(),
                p.getStockStatus().name(), p.getSupplier() == null ? null : p.getSupplier().getName())).toList();
        return new Inventory(list.size(), units, round(value.doubleValue()), in, low, out, categories, reorderItems);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
