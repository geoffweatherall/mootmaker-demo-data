package com.mootmaker.tools.sampledatatopup;

import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers {@link SampleDataTopUp#weekdaysBetween}, the pure date-range logic behind picking which days to top up. */
class SampleDataTopUpTest {

    @Test
    void includesOnlyMondayThroughFridayAcrossAFullWeek() {
        // 2026-07-27 is a Monday.
        final LocalDate monday = LocalDate.of(2026, 7, 27);

        final List<LocalDate> weekdays = SampleDataTopUp.weekdaysBetween(monday, monday.plusDays(7));

        assertEquals(List.of(
                LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 29),
                LocalDate.of(2026, 7, 30), LocalDate.of(2026, 7, 31)),
                weekdays);
    }

    @Test
    void endIsExclusive() {
        final LocalDate monday = LocalDate.of(2026, 7, 27);

        final List<LocalDate> weekdays = SampleDataTopUp.weekdaysBetween(monday, monday.plusDays(1));

        assertEquals(List.of(monday), weekdays);
    }

    @Test
    void returnsNothingForAWeekendOnlyRange() {
        // 2026-08-01 is a Saturday, 2026-08-02 is a Sunday.
        final LocalDate saturday = LocalDate.of(2026, 8, 1);

        final List<LocalDate> weekdays = SampleDataTopUp.weekdaysBetween(saturday, saturday.plusDays(2));

        assertTrue(weekdays.isEmpty());
    }

    @Test
    void sixWeekWindowContainsExactlyThirtyWeekdays() {
        final LocalDate monday = LocalDate.of(2026, 7, 27);

        final List<LocalDate> weekdays = SampleDataTopUp.weekdaysBetween(monday, monday.plusDays(42));

        assertEquals(30, weekdays.size(), "6 weeks * 5 weekdays");
    }

    @Test
    void returnsNothingWhenStartEqualsEnd() {
        final LocalDate day = LocalDate.of(2026, 7, 27);

        assertTrue(SampleDataTopUp.weekdaysBetween(day, day).isEmpty());
    }
}
