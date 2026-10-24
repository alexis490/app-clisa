package com.example.app_clisa.ui.reports

import java.time.LocalDate
import java.time.YearMonth

internal fun calendarDays(month: YearMonth): List<LocalDate> {
    val offset = month.atDay(1).dayOfWeek.value - 1 // Monday first.
    val first = month.atDay(1).minusDays(offset.toLong())
    val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    return List(cells) { first.plusDays(it.toLong()) }
}

internal data class ReportDateSelection(val start: LocalDate?, val end: LocalDate?, val selectingEnd: Boolean = false) {
    val valid: Boolean get() = start != null && end != null && end >= start && end <= start.plusDays(365)

    fun select(date: LocalDate): ReportDateSelection =
        if (!selectingEnd || start == null || date < start) ReportDateSelection(date, null, true)
        else copy(end = date, selectingEnd = false)
}
