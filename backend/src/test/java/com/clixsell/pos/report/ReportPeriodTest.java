package com.clixsell.pos.report;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U5: the text bounds of a period must hold every form of a stored time of its days.
 * The app stores {@code LocalDateTime.toString()}, which leaves out ":00" seconds:
 * the first second after midnight is "2026-11-01T00:00".
 */
class ReportPeriodTest {

    private static boolean inside(ReportPeriod p, String stored) {
        return stored.compareTo(p.startText()) >= 0 && stored.compareTo(p.endTextExclusive()) < 0;
    }

    @Test
    void everyStoredFormOfADayIsInsideItsPeriodAndNothingElse() {
        ReportPeriod nov = new ReportPeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        for (String t : List.of("2026-11-01T00:00", "2026-11-01T00:00:00", "2026-11-01T00:00:00.5",
                "2026-11-01T00:00:01", "2026-11-15T10:15", "2026-11-30T23:59", "2026-11-30T23:59:59",
                "2026-11-30T23:59:59.999")) {
            assertThat(inside(nov, t)).as(t + " belongs to November").isTrue();
        }
        for (String t : List.of("2026-10-31T23:59", "2026-10-31T23:59:59", "2026-10-31T23:59:59.999",
                "2026-12-01T00:00", "2026-12-01T00:00:00", "2026-12-01T00:00:01")) {
            assertThat(inside(nov, t)).as(t + " is not in November").isFalse();
        }
    }

    @Test
    void oneDayAndAWholeYear() {
        ReportPeriod day = new ReportPeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        assertThat(inside(day, "2026-11-01T00:00")).isTrue();
        assertThat(inside(day, "2026-11-02T00:00")).isFalse();
        ReportPeriod year = ReportPeriod.ofYear(2027);
        assertThat(inside(year, "2027-01-01T00:00")).isTrue();
        assertThat(inside(year, "2026-12-31T23:59:59")).isFalse();
        assertThat(inside(ReportPeriod.ofYear(2026), "2027-01-01T00:00")).isFalse();
    }
}
