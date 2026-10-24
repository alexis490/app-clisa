package com.example.app_clisa.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import java.time.LocalDate
import java.util.Date

data class ReportPage(val records: List<ReportRecord>, val cursor: DocumentSnapshot?, val hasMore: Boolean)

class ReportRepository {
    private val db = FirebaseFirestore.getInstance()
    suspend fun page(start: LocalDate, end: LocalDate, after: DocumentSnapshot? = null): ReportPage {
        FirestoreAccess.requireModule(AppModule.REPORTS)
        require(!end.isBefore(start) && end <= start.plusDays(365)) { "Selecciona un período de hasta 366 días." }
        // One range field: uses the automatic fechaHora index, no collection listener.
        var query = db.collection("registros")
            .whereGreaterThanOrEqualTo("fechaHora", Timestamp(Date.from(start.atStartOfDay(reportZone).toInstant())))
            .whereLessThan("fechaHora", Timestamp(Date.from(end.plusDays(1).atStartOfDay(reportZone).toInstant())))
            .orderBy("fechaHora", Query.Direction.DESCENDING).limit(100)
        if (after != null) query = query.startAfter(after)
        val documents = query.get(Source.SERVER).inventoryAwait().documents
        val records = documents.mapNotNull { document ->
            if (document.getString("tipo") != "SALIDA" || document.getString("estado") != "COMPLETADO") return@mapNotNull null
            val time = document.getTimestamp("fechaHora")?.toDate()?.toInstant() ?: return@mapNotNull null
            fun text(field: String) = document.getString(field)?.trim()?.takeIf { it.isNotEmpty() }
            ReportRecord(document.id, text("productoId") ?: "sin-producto:${document.id}", text("nombreProducto") ?: "No registrado", text("codigoProducto") ?: "No registrado",
                document.getLong("cantidad") ?: 0, text("registradoPorId") ?: "sin-usuario:${document.id}", text("registradoPorNombre") ?: "No registrado",
                text("recibidoPor") ?: "No registrado", text("tiendaOrigenId").orEmpty(), text("tiendaOrigenNombre") ?: "Sin tienda",
                text("tiendaDestinoId").orEmpty(), text("tiendaDestinoNombre") ?: "Sin tienda", text("estadoPago"), time)
        }
        return ReportPage(records, documents.lastOrNull(), documents.size == 100)
    }
}
