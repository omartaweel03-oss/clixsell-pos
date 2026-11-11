package com.clixsell.pos.report;

import com.clixsell.pos.common.ValidationException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A reporting period: whole days from {@code from} to {@code to}, both included.
 * In the database it is the half-open text range [from 00:00:00, to+1 00:00:00),
 * which matches the ISO date-time text the app stores ("2026-11-04T10:15:00").
 */
public record ReportPeriod(LocalDate from, LocalDate to) {

    /** Longest period accepted (a bit more than one year, for daily lists). */
    static final int MAX_DAYS = 400;

    public static ReportPeriod of(LocalDate from, LocalDate to) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (from == null) errors.put("from", "Start date is required (yyyy-MM-dd)");
        if (to == null) errors.put("to", "End date is required (yyyy-MM-dd)");
        if (errors.isEmpty() && to.isBefore(from)) errors.put("to", "End date must not be before the start date");
        if (errors.isEmpty() && ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            errors.put("to", "The period can be at most " + MAX_DAYS + " days");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new ReportPeriod(from, to);
    }

    public static ReportPeriod ofYear(Integer year) {
        if (year == null || year < 2000 || year > 2100) {
            throw new ValidationException(Map.of("year", "Year must be between 2000 and 2100"));
        }
        return new ReportPeriod(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /** The period of the same length just before this one (for "vs. previous period"). */
    public ReportPeriod previous() {
        // A whole calendar year compares with the whole previous year.
        if (from.getDayOfYear() == 1 && to.equals(from.withMonth(12).withDayOfMonth(31))) {
            return new ReportPeriod(from.minusYears(1), to.minusYears(1));
        }
        // A whole calendar month compares with the whole previous month.
        if (from.getDayOfMonth() == 1 && to.equals(from.withDayOfMonth(from.lengthOfMonth()))) {
            LocalDate start = from.minusMonths(1);
            return new ReportPeriod(start, start.withDayOfMonth(start.lengthOfMonth()));
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return new ReportPeriod(from.minusDays(days), from.minusDays(1));
    }

    String startText() {
        return from + "T00:00:00";
    }

    String endTextExclusive() {
        return to.plusDays(1) + "T00:00:00";
    }
}
