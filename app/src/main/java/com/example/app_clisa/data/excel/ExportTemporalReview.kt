package com.example.app_clisa.data.excel

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Operator declaration only. Never initialized from file metadata or the import clock. */
data class ManualExportTime(val date: LocalDate? = null, val time: LocalTime? = null, val uncertain: Boolean = false) {
    fun epochMillis(): Long? = if (date == null || time == null) null else date.atTime(time).atZone(ZONE).toInstant().toEpochMilli()
    companion object { val ZONE: ZoneId = ZoneId.of("America/Lima") }
}

enum class ExportTimeState { UNKNOWN, FUTURE, DOUBTFUL, STALE, DECLARED_UNVERIFIED }
enum class MovementPosition { BEFORE, AT_CUTOFF, AFTER, UNKNOWN }
enum class TemporalDecision { REQUIRES_REVIEW, BLOCKED }
data class InventoryMovement(val id: String, val type: String?, val productId: String?, val quantity: Long?,
    val recordedAtMillis: Long?, val state: String?, val serverTimeProven: Boolean = false,
    val reflectedInExportProven: Boolean = false)

/** A bounded query does not prove the absence of malformed/missing timestamps outside its index. */
data class MovementReadSnapshot(val cutoffMillis: Long, val throughMillis: Long, val movements: List<InventoryMovement>,
    val hasMore: Boolean, val indexedRangeFinished: Boolean)
data class ReviewedInventoryMovement(val movement: InventoryMovement, val position: MovementPosition)
data class ExportTemporalReview(val declaration: ManualExportTime, val timeState: ExportTimeState,
    val decision: TemporalDecision, val blockers: List<String>, val movements: List<ReviewedInventoryMovement>,
    val historyConsulted: Boolean, val historyHasMore: Boolean, val historyThroughMillis: Long?) {
    val cutoffReconciled: Boolean get() = false // No authoritative cutoff/reconciliation protocol exists in 4A.2.
    val productionExecutable: Boolean get() = false
    fun identityValue(): List<Any?> = listOf(declaration.date?.toString(), declaration.time?.toString(), declaration.uncertain,
        timeState.name, decision.name, blockers.sorted(), historyConsulted, historyHasMore, historyThroughMillis,
        movements.map { row -> listOf(row.movement.id, row.movement.type, row.movement.productId, row.movement.quantity,
            row.movement.recordedAtMillis, row.movement.state, row.movement.serverTimeProven,
            row.movement.reflectedInExportProven, row.position.name) }.sortedBy { SyncCanonical.value(it) })
}

object ExportTemporalReviewer {
    // Conservative review threshold, not a certified business cutoff and not an authorization window.
    const val DEFAULT_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
    fun review(declaration: ManualExportTime = ManualExportTime(), history: MovementReadSnapshot? = null,
        nowMillis: Long, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): ExportTemporalReview {
        require(nowMillis >= 0 && maxAgeMillis > 0)
        val cutoff = declaration.epochMillis()
        val state = when {
            cutoff == null -> ExportTimeState.UNKNOWN
            cutoff > nowMillis -> ExportTimeState.FUTURE
            declaration.uncertain -> ExportTimeState.DOUBTFUL
            nowMillis - cutoff > maxAgeMillis -> ExportTimeState.STALE
            else -> ExportTimeState.DECLARED_UNVERIFIED
        }
        val faults = mutableListOf("UNVERIFIED_EXPORT_CUTOFF")
        when (state) {
            ExportTimeState.UNKNOWN -> faults += "EXPORT_TIME_UNKNOWN"
            ExportTimeState.FUTURE -> faults += "EXPORT_TIME_IN_FUTURE"
            ExportTimeState.DOUBTFUL -> faults += "EXPORT_TIME_UNCERTAIN"
            ExportTimeState.STALE -> faults += "EXPORT_TOO_OLD"
            else -> Unit
        }
        val usable = history != null && cutoff != null && history.cutoffMillis == cutoff &&
            history.throughMillis >= cutoff && history.throughMillis <= nowMillis
        if (history != null && !usable) faults += "HISTORY_WINDOW_MISMATCH"
        if (!usable) faults += "HISTORY_NOT_REVIEWED"
        val rows = if (!usable) emptyList() else history!!.movements.map { m ->
            ReviewedInventoryMovement(m, when {
                m.recordedAtMillis == null || m.recordedAtMillis < 0 || m.recordedAtMillis > history.throughMillis -> MovementPosition.UNKNOWN
                m.recordedAtMillis < cutoff!! -> MovementPosition.BEFORE
                m.recordedAtMillis == cutoff -> MovementPosition.AT_CUTOFF
                else -> MovementPosition.AFTER
            })
        }.sortedWith(compareBy({ it.movement.recordedAtMillis }, { it.movement.id }))
        if (usable) {
            // The index cannot expose documents with no fechaHora, nor prove accounting in the external system.
            faults += "HISTORY_SCOPE_UNPROVEN"
            if (history!!.hasMore || !history.indexedRangeFinished) faults += "HISTORY_PAGE_LIMIT_REACHED"
            if (rows.any { it.position == MovementPosition.AFTER }) faults += "POST_EXPORT_MOVEMENTS_REQUIRE_REVIEW"
            if (rows.any { it.position == MovementPosition.AT_CUTOFF }) faults += "MOVEMENT_AT_DECLARED_CUTOFF"
            if (rows.any { it.position == MovementPosition.UNKNOWN || !it.movement.serverTimeProven }) faults += "MOVEMENT_TIMESTAMP_UNRELIABLE"
            if (rows.any { !it.movement.reflectedInExportProven }) faults += "MOVEMENT_STOCK_RELATION_UNKNOWN"
            if (rows.any { it.movement.type !in setOf("SALIDA", "AJUSTE_ENTRADA", "AJUSTE_SALIDA") ||
                it.movement.state != "COMPLETADO" || it.movement.productId.isNullOrBlank() ||
                it.movement.quantity == null || it.movement.quantity <= 0 }) faults += "MOVEMENT_SCHEMA_REQUIRES_REVIEW"
            if (rows.groupBy { it.movement.id }.any { it.key.isBlank() || it.value.size != 1 }) faults += "DUPLICATE_MOVEMENT_ID"
        }
        return ExportTemporalReview(declaration, state,
            if (state == ExportTimeState.DECLARED_UNVERIFIED) TemporalDecision.REQUIRES_REVIEW else TemporalDecision.BLOCKED,
            faults.distinct().sorted(), rows, usable, usable && history!!.hasMore, if (usable) history!!.throughMillis else null)
    }
}
