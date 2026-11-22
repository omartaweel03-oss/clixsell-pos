package com.clixsell.pos.guardian;

import com.clixsell.pos.activity.ActivityEventType;
import com.clixsell.pos.activity.ActivityLogService;
import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.guardian.GuardianResponses.ActivityPage;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.AlertList;
import com.clixsell.pos.guardian.GuardianResponses.Dashboard;
import com.clixsell.pos.guardian.GuardianResponses.DaySales;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.GuardianResponses.Status;
import com.clixsell.pos.guardian.GuardianResponses.TodaySales;
import com.clixsell.pos.report.ReportPeriod;
import com.clixsell.pos.report.ReportService;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.store.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guardian Mode, Phase 1 (report Figure 3.1, UR-M16 / SR30, UR-M17 / SR31).
 *
 * <p>Rule-based, read-only analysis of ONE store (the logged-in Store Admin's):
 * it never changes a price, a stock quantity or a discount and never sends
 * anything. The only things it writes are the store's on / off switch and the
 * matching activity log row.
 *
 * <p>While Guardian Mode is off nothing is analyzed or shown; the activity log
 * keeps recording (decision D6) and no data is deleted.
 */
@Service
@Transactional(readOnly = true)
public class GuardianService {

    static final int ACTIVITY_PAGE_SIZE = 50;
    static final Set<String> RULE_IDS = Set.of("G1", "G2a", "G2b", "G3", "G4", "G5", "G6", "G7", "G8a", "G8b", "G9");

    private final GuardianRepository repo;
    private final StoreRepository stores;
    private final ReportService reports;
    private final ActivityLogService activity;

    public GuardianService(GuardianRepository repo, StoreRepository stores, ReportService reports,
                           ActivityLogService activity) {
        this.repo = repo;
        this.stores = stores;
        this.activity = activity;
        this.reports = reports;
    }

    /** The chosen month: both values, or neither (= the current month). Not in the future. */
    record Period(LocalDate from, LocalDate to, boolean current) {

        static Period of(Integer year, Integer month, LocalDate today) {
            YearMonth now = YearMonth.from(today);
            if (year == null && month == null) return new Period(now.atDay(1), today, true);
            if (year == null) throw new ValidationException(Map.of("year", "Choose the year too"));
            if (month == null) throw new ValidationException(Map.of("month", "Choose the month too"));
            if (year < 2000 || year > 2100) throw new ValidationException(Map.of("year", "Year must be between 2000 and 2100"));
            if (month < 1 || month > 12) throw new ValidationException(Map.of("month", "Month must be between 1 and 12"));
            YearMonth ym = YearMonth.of(year, month);
            if (ym.isAfter(now)) throw new ValidationException(Map.of("month", "This month has not started yet"));
            boolean current = ym.equals(now);
            return new Period(ym.atDay(1), current ? today : ym.atEndOfMonth(), current);
        }
    }

    public Status status(long storeId) {
        Store store = stores.findById(storeId).orElseThrow();
        Object[] last = repo.lastToggle(storeId);
        return new Status(store.isGuardianEnabled(), last == null ? null : (LocalDateTime) last[0],
                last == null ? null : (String) last[1], repo.trackedSince(storeId));
    }

    /** Turns Guardian Mode on or off for the logged-in Store Admin's store; logged only when it changes. */
    @Transactional
    public Status setStatus(CurrentUser user, Boolean enabled) {
        if (enabled == null) throw new ValidationException(Map.of("enabled", "Send true or false"));
        long storeId = user.requireStoreId();
        Store store = stores.findById(storeId).orElseThrow();
        if (store.setGuardianEnabled(enabled)) {
            stores.save(store);
            activity.record(user, enabled ? ActivityEventType.GUARDIAN_ENABLED : ActivityEventType.GUARDIAN_DISABLED,
                    "STORE", storeId, store.getName(), null);
        }
        return status(storeId);
    }

    public Dashboard dashboard(long storeId, Integer year, Integer month) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Period period = Period.of(year, month, now.toLocalDate());
        Status status = status(storeId);
        if (!status.enabled()) return Dashboard.disabled(status);
        GuardianAnalysis.Result r = GuardianAnalysis.analyze(load(storeId, period, now, status));
        List<Alert> top = r.alerts().subList(0, Math.min(3, r.alerts().size()));
        return new Dashboard(true, status, period.from(), period.to(), period.current(), todaySales(storeId, now),
                r.criticalCount(), r.profitRiskCount(), r.costData(), r.health(), top, r.alerts().size(),
                r.recommendations(), r.storeMap(), r.atRisk(), r.profitProtection(), r.activity());
    }

    public AlertList alerts(long storeId, Integer year, Integer month, String severity, String rule) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Period period = Period.of(year, month, now.toLocalDate());
        Severity sev = parseSeverity(severity);
        if (rule != null && !RULE_IDS.contains(rule)) {
            throw new ValidationException(Map.of("rule", "Unknown rule: " + rule));
        }
        Status status = status(storeId);
        if (!status.enabled()) return new AlertList(false, null, null, List.of());
        List<Alert> alerts = GuardianAnalysis.alerts(load(storeId, period, now, status)).stream()
                .filter(a -> sev == null || a.severity() == sev)
                .filter(a -> rule == null || a.rule().equals(rule))
                .toList();
        return new AlertList(true, period.from(), period.to(), alerts);
    }

    public ActivityPage activity(long storeId, Integer year, Integer month, String type, Integer page) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Period period = Period.of(year, month, now.toLocalDate());
        if (type != null && Arrays.stream(ActivityEventType.values()).noneMatch(t -> t.name().equals(type))) {
            throw new ValidationException(Map.of("type", "Unknown activity type: " + type));
        }
        int p = page == null ? 0 : page;
        if (p < 0 || p > 100_000) throw new ValidationException(Map.of("page", "Page must be 0 or more"));
        Status status = status(storeId);
        if (!status.enabled()) {
            return new ActivityPage(false, null, null, p, ACTIVITY_PAGE_SIZE, 0, null, List.of());
        }
        String start = period.from() + "T00:00:00";
        String end = period.to().plusDays(1) + "T00:00:00";
        return new ActivityPage(true, period.from(), period.to(), p, ACTIVITY_PAGE_SIZE,
                repo.activityCount(storeId, start, end, type), status.activityTrackedSince(),
                repo.activity(storeId, start, end, type, ACTIVITY_PAGE_SIZE, p * ACTIVITY_PAGE_SIZE));
    }

    private GuardianData load(long storeId, Period period, LocalDateTime now, Status status) {
        Store store = stores.findById(storeId).orElseThrow();
        return repo.load(storeId, period.from(), period.to(), period.current(), now, store.getMaxDiscountPercent(),
                status.activityTrackedSince());
    }

    /** Net sales of today so far vs. yesterday up to the same time, and the last 7 days (as Sales Analytics). */
    TodaySales todaySales(long storeId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        double todayNet = Amounts.round2(repo.netSales(storeId, today.atStartOfDay(), now));
        double yesterday = Amounts.round2(repo.netSales(storeId, today.minusDays(1).atStartOfDay(), now.minusDays(1)));
        Double change = yesterday > 0 ? Amounts.round1((todayNet - yesterday) * 100 / yesterday) : null;
        List<DaySales> days = reports.dailySales(storeId, ReportPeriod.of(today.minusDays(6), today)).stream()
                .map(p -> new DaySales(p.date(), p.netSales())).toList();
        return new TodaySales(todayNet, yesterday, change, days);
    }

    private static Severity parseSeverity(String s) {
        if (s == null) return null;
        try {
            return Severity.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(Map.of("severity", "Severity must be HIGH, MEDIUM or LOW"));
        }
    }
}
