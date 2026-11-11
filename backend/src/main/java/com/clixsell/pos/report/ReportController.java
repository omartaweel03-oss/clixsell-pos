package com.clixsell.pos.report;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.report.ReportResponses.DayPoint;
import com.clixsell.pos.report.ReportResponses.Inventory;
import com.clixsell.pos.report.ReportResponses.MonthlySales;
import com.clixsell.pos.report.ReportResponses.Summary;
import com.clixsell.pos.report.ReportResponses.TopProducts;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Reports (Figure 3.8) and Sales Analytics (Figure 3.7) API – Store Admin
 * (the report's Manager) only, always for the logged-in user's own store.
 * There is no store id parameter: the store comes from the session.
 *
 * <pre>
 * GET /api/reports/summary?from=2026-11-01&amp;to=2026-11-30      cards: sales, invoices, returns, profit status
 * GET /api/reports/daily-sales?from=…&amp;to=…                     one point per day (0 when no sales)
 * GET /api/reports/monthly-sales?year=2026                       12 months
 * GET /api/reports/top-products?from=…&amp;to=…&amp;limit=7          best-selling products
 * GET /api/reports/inventory                                     inventory report + products to reorder
 * </pre>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal CurrentUser user,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.summary(user.storeId(), ReportPeriod.of(from, to));
    }

    @GetMapping("/daily-sales")
    public List<DayPoint> dailySales(@AuthenticationPrincipal CurrentUser user,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.dailySales(user.storeId(), ReportPeriod.of(from, to));
    }

    @GetMapping("/monthly-sales")
    public MonthlySales monthlySales(@AuthenticationPrincipal CurrentUser user,
                                     @RequestParam(required = false) Integer year) {
        ReportPeriod.ofYear(year);
        return service.monthlySales(user.storeId(), year);
    }

    @GetMapping("/top-products")
    public TopProducts topProducts(@AuthenticationPrincipal CurrentUser user,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(required = false) Integer limit) {
        return service.topProducts(user.storeId(), ReportPeriod.of(from, to), limit);
    }

    @GetMapping("/inventory")
    public Inventory inventory(@AuthenticationPrincipal CurrentUser user) {
        return service.inventory(user.storeId());
    }
}
