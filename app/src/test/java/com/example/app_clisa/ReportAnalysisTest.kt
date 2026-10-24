package com.example.app_clisa

import com.example.app_clisa.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ReportAnalysisTest {
    private fun record(id: String, quantity: Long = 2, payment: String? = "Pagado", product: String = "p", user: String = "u") =
        ReportRecord(id, product, "Producto $product", "CODE-$product", quantity, user, "Usuario $user", "Receptor", "clisa", "CLISA", "toqen", "TOQEN", payment, Instant.parse("2026-10-16T04:30:00Z"))
    @Test fun computesExitCountAndUnitsSeparately() {
        val totals = ReportAnalysis.totals(listOf(record("1", 2), record("2", 5, "Pendiente"), record("3", 1, null)))
        assertEquals(3, totals.exits); assertEquals(8L, totals.units)
        assertEquals(1, totals.paid); assertEquals(1, totals.pending); assertEquals(1, totals.paymentUnknown)
    }
    @Test fun filtersComposeAndBothTransferStoresCanBeSelected() {
        val records = listOf(record("1"), record("2", payment = "Pendiente", product = "other"))
        assertEquals(1, ReportAnalysis.filter(records, ReportFilter(storeId = "toqen", productId = "p", payment = "Pagado")).size)
        assertEquals(2, ReportAnalysis.filter(records, ReportFilter(storeId = "clisa")).size)
        assertTrue(ReportAnalysis.filter(records, ReportFilter(storeId = "unrelated")).isEmpty())
    }
    @Test fun deduplicatesRecordsAcrossPages() {
        assertEquals(1, ReportAnalysis.filter(listOf(record("1"), record("1")), ReportFilter()).size)
    }
    @Test fun ranksProductsByUnitsAndUsersByMovements() {
        val records = listOf(record("1", 4, product = "a"), record("2", 1, product = "b"), record("3", 2, product = "b", user = "v"))
        assertEquals("a", ReportAnalysis.products(records).first().id)
        assertEquals(4L, ReportAnalysis.products(records).first().quantity)
        assertEquals(2, ReportAnalysis.users(records).first().movements)
    }
    @Test fun countsTransferOriginAndDestinationWithoutInventingIndependentEntries() {
        val stores = ReportAnalysis.stores(listOf(record("1"), record("2")))
        assertEquals(2, stores.first { it.id == "clisa" }.outgoing)
        assertEquals(0, stores.first { it.id == "clisa" }.incoming)
        assertEquals(2, stores.first { it.id == "toqen" }.incoming)
    }
    @Test fun groupsDatesInLimaAndIncludesDaysWithNoMovements() {
        val start = LocalDate.parse("2026-10-14")
        val buckets = ReportAnalysis.dates(listOf(record("1")), start, start.plusDays(2), ReportInterval.DAY)
        assertEquals(listOf(0, 1, 0), buckets.map { it.count })
    }
    @Test fun groupsWeeksStartingMonday() {
        val day = LocalDate.parse("2026-10-15")
        assertEquals(LocalDate.parse("2026-10-12"), ReportAnalysis.dates(listOf(record("1")), day, day, ReportInterval.WEEK).single().date)
    }
    @Test fun csvQuotesTextAndPreventsSpreadsheetFormulaExecution() {
        val item = record("1").copy(product = "=HYPERLINK(\"example\")\nNombre")
        val csv = ReportAnalysis.csv(listOf(item), LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-15"), false)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("'=HYPERLINK(\"\"example\"\")\nNombre"))
        assertTrue(csv.contains("2026-10-15T23:30"))
    }
    @Test fun unknownPaymentIsNotSilentlyClassifiedAsPending() {
        val records = listOf(record("1", payment = "DESCONOCIDO"))
        assertEquals(0, ReportAnalysis.totals(records).pending)
        assertEquals(1, ReportAnalysis.filter(records, ReportFilter(payment = "SIN_DATO")).size)
    }
}
