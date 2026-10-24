package com.example.app_clisa.data.excel

import com.example.app_clisa.data.AppModule
import com.example.app_clisa.data.FirestoreAccess
import com.example.app_clisa.data.inventoryAwait
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit per-product query, including pre-export records and missing dates. Never a global listener. */
internal class FirestoreReconciliationReader(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    suspend fun read(productId: String): ProductMovementSnapshot {
        require(productId.isNotBlank() && '/' !in productId)
        val uid = FirestoreAccess.requireModule(AppModule.PRODUCTS)
        val rows = mutableListOf<TransferMovement>()
        var cursor: DocumentSnapshot? = null
        repeat(4) {
            currentCoroutineContext().ensureActive()
            check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "La sesión cambió." }
            var query = db.collection("registros").whereEqualTo("productoId", productId).orderBy(FieldPath.documentId()).limit(50)
            cursor?.let { query = query.startAfter(it) }
            val docs = query.get(Source.SERVER).inventoryAwait().documents
            rows += docs.map { d ->
                fun time(field: String) = (d.get(field) as? Timestamp)?.toDate()?.time
                fun versionValue(v: Any?): Any? = when (v) {
                    null, is String, is Long, is Int, is Boolean -> v
                    is Timestamp -> listOf("timestamp", v.seconds, v.nanoseconds)
                    is Double -> listOf("double", v.toString())
                    is List<*> -> v.map { versionValue(it) }
                    is Map<*, *> -> v.entries.associate { (k, value) -> k.toString() to versionValue(value) }
                    else -> error("Tipo de campo no verificable; el movimiento permanece bloqueado.")
                }
                TransferMovement(d.id, d.get("productoId") as? String, d.get("codigoProducto") as? String,
                    d.get("existenciaId") as? String, d.get("codigoExistencia") as? String, d.get("cantidad") as? Long,
                    d.get("tipo") as? String, d.get("estado") as? String, d.get("estadoPago") as? String,
                    d.get("estadoFormalizacion") as? String, time("fechaHora"), time("formalizadoEn"), d.get("formalizadoPor") as? String,
                    d.get("tiendaOrigenId") as? String, d.get("tiendaDestinoId") as? String,
                    SyncCanonical.hash(versionValue(d.data)))
            }
            if (docs.size < 50) {
                check(FirestoreAccess.requireModule(AppModule.PRODUCTS) == uid)
                return ProductMovementSnapshot(productId, rows.toList(), true, System.currentTimeMillis())
            }
            cursor = docs.last()
        }
        check(FirestoreAccess.requireModule(AppModule.PRODUCTS) == uid)
        return ProductMovementSnapshot(productId, rows.toList(), false, System.currentTimeMillis())
    }
}
