package com.example.app_clisa.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

val reportZone: ZoneId = ZoneId.of("America/Lima")
data class ReportRecord(val id: String, val productId: String, val product: String, val code: String, val quantity: Long,
    val userId: String, val user: String, val receiver: String, val originId: String, val origin: String,
    val destinationId: String, val destination: String, val payment: String?, val time: Instant)
data class ReportFilter(val storeId: String = "", val productId: String = "", val userId: String = "", val payment: String = "")
data class RankedProduct(val id: String, val name: String, val code: String, val quantity: Long)
data class RankedUser(val id: String, val name: String, val movements: Int)
data class StoreMovements(val id: String, val name: String, val outgoing: Int, val incoming: Int)
data class ReportTotals(val exits: Int, val units: Long, val paid: Int, val pending: Int, val paymentUnknown: Int)
enum class ReportInterval { DAY, WEEK, MONTH }
data class ReportBucket(val date: LocalDate, val count: Int)

object ReportAnalysis {
    fun filter(records: List<ReportRecord>, filter: ReportFilter) = records.distinctBy { it.id }.filter {
        (filter.storeId.isEmpty() || it.originId == filter.storeId || it.destinationId == filter.storeId) &&
            (filter.productId.isEmpty() || it.productId == filter.productId) &&
            (filter.userId.isEmpty() || it.userId == filter.userId) &&
            (filter.payment.isEmpty() || (if (filter.payment == "SIN_DATO") it.payment !in listOf("Pagado", "Pendiente") else it.payment == filter.payment))
    }
    fun totals(records: List<ReportRecord>) = ReportTotals(records.size, records.sumOf { it.quantity },
        records.count { it.payment == "Pagado" }, records.count { it.payment == "Pendiente" },
        records.count { it.payment !in listOf("Pagado", "Pendiente") })
    fun products(records: List<ReportRecord>) = records.groupBy { it.productId }.map { (id, entries) ->
        RankedProduct(id, entries.first().product, entries.first().code, entries.sumOf { it.quantity })
    }.sortedWith(compareByDescending<RankedProduct> { it.quantity }.thenBy { it.name }.thenBy { it.id })
    fun users(records: List<ReportRecord>) = records.groupBy { it.userId }.map { (id, entries) ->
        RankedUser(id, entries.first().user, entries.size)
    }.sortedWith(compareByDescending<RankedUser> { it.movements }.thenBy { it.name }.thenBy { it.id })
    fun stores(records: List<ReportRecord>): List<StoreMovements> {
        val names = linkedMapOf<String, String>()
        records.forEach { record ->
            if (record.originId.isNotBlank()) names[record.originId] = record.origin
            if (record.destinationId.isNotBlank()) names[record.destinationId] = record.destination
        }
        return names.map { (id, name) -> StoreMovements(id, name, records.count { it.originId == id }, records.count { it.destinationId == id }) }.sortedBy { it.name }
    }
    fun dates(records: List<ReportRecord>, start: LocalDate, end: LocalDate, interval: ReportInterval): List<ReportBucket> {
        fun key(date: LocalDate) = when (interval) {
            ReportInterval.DAY -> date
            ReportInterval.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ReportInterval.MONTH -> date.withDayOfMonth(1)
        }
        val counts = records.groupingBy { key(it.time.atZone(reportZone).toLocalDate()) }.eachCount()
        val result = mutableListOf<ReportBucket>()
        var cursor = key(start)
        while (cursor <= key(end)) {
            result += ReportBucket(cursor, counts[cursor] ?: 0)
            cursor = when (interval) { ReportInterval.DAY -> cursor.plusDays(1); ReportInterval.WEEK -> cursor.plusWeeks(1); ReportInterval.MONTH -> cursor.plusMonths(1) }
        }
        return result
    }
    /** Semicolon-delimited UTF-8 CSV; neutralize spreadsheet formulas in untrusted text. */
    fun csv(records: List<ReportRecord>, start: LocalDate, end: LocalDate, summary: Boolean, filter: ReportFilter = ReportFilter()): String {
        fun cell(value: String): String {
            val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        val rows = mutableListOf(listOf("CLISA", "Salidas completadas", "$start / $end"))
        rows += listOf("Filtro de tienda (ID)", filter.storeId.ifEmpty { "Todas" })
        rows += listOf("Filtro de producto (ID)", filter.productId.ifEmpty { "Todos" })
        rows += listOf("Filtro de usuario (ID)", filter.userId.ifEmpty { "Todos" })
        rows += listOf("Filtro de pago", filter.payment.ifEmpty { "Todos" })
        if (summary) {
            val totals = totals(records)
            rows += listOf("Total de salidas", totals.exits.toString())
            rows += listOf("Unidades retiradas", totals.units.toString())
            rows += listOf("Pagados", totals.paid.toString())
            rows += listOf("Pendientes", totals.pending.toString())
            rows += listOf("Pago no registrado", totals.paymentUnknown.toString())
            rows += listOf("Producto", "Código", "Unidades retiradas")
            products(records).forEach { rows += listOf(it.name, it.code, it.quantity.toString()) }
        } else {
            rows += listOf("Registro", "Fecha y hora (Lima)", "Producto", "Código", "Cantidad", "Registrado por", "Receptor", "Origen", "Destino", "Pago")
            records.forEach { rows += listOf(it.id, it.time.atZone(reportZone).toLocalDateTime().toString(), it.product, it.code,
                it.quantity.toString(), it.user, it.receiver, it.origin, it.destination, it.payment ?: "No registrado") }
        }
        return "\uFEFF" + rows.joinToString("\r\n") { row -> row.joinToString(";") { cell(it) } } + "\r\n"
    }
}
