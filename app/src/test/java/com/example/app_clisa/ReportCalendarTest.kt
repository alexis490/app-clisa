package com.example.app_clisa

import com.example.app_clisa.ui.reports.ReportDateSelection
import com.example.app_clisa.ui.reports.calendarDays
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class ReportCalendarTest {
    @Test fun monthGridAlwaysStartsOnMondayAndContainsEveryDayExactlyOnce() {
        for (year in 2024..2027) for (number in 1..12) {
            val month = YearMonth.of(year, number)
            val days = calendarDays(month)
            assertEquals(DayOfWeek.MONDAY, days.first().dayOfWeek)
            assertEquals(DayOfWeek.SUNDAY, days.last().dayOfWeek)
            assertEquals(0, days.size % 7)
            assertEquals(month.lengthOfMonth(), days.count { YearMonth.from(it) == month })
            assertEquals(days.size, days.distinct().size)
            days.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
        }
    }
    @Test fun leapFebruaryIncludes29ButFebruary2026DoesNot() {
        assertTrue(calendarDays(YearMonth.of(2024, 2)).contains(LocalDate.of(2024, 2, 29)))
        assertEquals(28, calendarDays(YearMonth.of(2026, 2)).count { it.monthValue == 2 })
    }
    @Test fun choosingStartClearsPreviousEndUntilSecondSelection() {
        val first = LocalDate.of(2026, 10, 13)
        val selection = ReportDateSelection(first, first.plusDays(6)).select(first.plusDays(1))
        assertEquals(first.plusDays(1), selection.start)
        assertNull(selection.end)
        assertTrue(selection.selectingEnd)
        assertFalse(selection.valid)
        val complete = selection.select(first.plusDays(4))
        assertTrue(complete.valid)
        assertFalse(complete.selectingEnd)
        assertEquals(first.plusDays(4), complete.end)
    }
    @Test fun endingBeforeStartBeginsNewRangeAndSameDayIsValid() {
        val date = LocalDate.of(2026, 10, 13)
        val reset = ReportDateSelection(date, null, true).select(date.minusDays(1))
        assertEquals(date.minusDays(1), reset.start)
        assertNull(reset.end)
        assertTrue(reset.selectingEnd)
        assertTrue(reset.select(date.minusDays(1)).valid)
    }
    @Test fun rangeCanCrossYearAndHasInclusiveLimitOf366Days() {
        val date = LocalDate.of(2025, 12, 30)
        assertTrue(ReportDateSelection(date, LocalDate.of(2026, 1, 4)).valid)
        assertTrue(ReportDateSelection(date, date.plusDays(365)).valid)
        assertFalse(ReportDateSelection(date, date.plusDays(366)).valid)
        assertFalse(ReportDateSelection(date, date.minusDays(1)).valid)
    }
}
