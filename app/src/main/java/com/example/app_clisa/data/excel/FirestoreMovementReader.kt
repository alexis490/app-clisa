package com.example.app_clisa.data.excel

import com.example.app_clisa.data.AppModule
import com.example.app_clisa.data.FirestoreAccess
import com.example.app_clisa.data.inventoryAwait
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Date

/** Explicit user action only: at most four 50-document pages. No listener, fallback scan or writes. */
internal class FirestoreMovementReader(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    suspend fun read(cutoffMillis: Long, throughMillis: Long, onProgress: (Int) -> Unit = {}): MovementReadSnapshot {
        require(cutoffMillis >= 0 && throughMillis >= cutoffMillis &&
            throughMillis - cutoffMillis <= ExportTemporalReviewer.DEFAULT_MAX_AGE_MILLIS)
        val uid = FirestoreAccess.requireModule(AppModule.PRODUCTS)
        val base = db.collection("registros").whereGreaterThanOrEqualTo("fechaHora", Timestamp(Date(cutoffMillis)))
            .whereLessThanOrEqualTo("fechaHora", Timestamp(Date(throughMillis))).orderBy("fechaHora")
        val rows = mutableListOf<InventoryMovement>()
        var cursor: com.google.firebase.firestore.DocumentSnapshot? = null
        repeat(MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "La sesión cambió. Vuelve a iniciar sesión." }
            var query = base.limit(PAGE_SIZE.toLong())
            cursor?.let { query = query.startAfter(it) }
            val docs = query.get(Source.SERVER).inventoryAwait().documents
            currentCoroutineContext().ensureActive()
            check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "La sesión cambió. Vuelve a iniciar sesión." }
            rows += docs.map { d -> InventoryMovement(d.id, d.get("tipo") as? String, d.get("productoId") as? String,
                d.get("cantidad") as? Long, (d.get("fechaHora") as? Timestamp)?.toDate()?.time, d.get("estado") as? String) }
            onProgress(rows.size)
            if (docs.size < PAGE_SIZE) {
                check(FirestoreAccess.requireModule(AppModule.PRODUCTS) == uid) { "La sesión cambió." }
                return MovementReadSnapshot(cutoffMillis, throughMillis, rows.toList(), false, true)
            }
            cursor = docs.last()
        }
        check(FirestoreAccess.requireModule(AppModule.PRODUCTS) == uid) { "La sesión cambió." }
        // Exactly 200 results is also conservatively 'may have more'; no extra query just to count.
        return MovementReadSnapshot(cutoffMillis, throughMillis, rows.toList(), true, false)
    }
    companion object { const val PAGE_SIZE = 50; const val MAX_PAGES = 4 }
}
