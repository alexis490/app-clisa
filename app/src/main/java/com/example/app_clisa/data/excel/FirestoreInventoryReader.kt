package com.example.app_clisa.data.excel

import com.example.app_clisa.data.AppModule
import com.example.app_clisa.data.FirestoreAccess
import com.example.app_clisa.data.inventoryAwait
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ComparisonProgress(val collection: String, val products: Int, val existences: Int)

/** Client SDK only: the deployed rules authorize every page. No IAM credentials or write API. */
internal class FirestoreInventoryReader(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    suspend fun read(onProgress: (ComparisonProgress) -> Unit): InventoryReadSnapshot {
        val started = System.currentTimeMillis()
        val uid = FirestoreAccess.requireModule(AppModule.PRODUCTS)
        val products = mutableListOf<ComparedProduct>()
        val existences = mutableListOf<ComparedExistence>()
        for (collection in listOf("productos", "existencias")) {
            var cursor: DocumentSnapshot? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "La sesión cambió. Vuelve a iniciar sesión." }
                val remaining = MAX_DOCUMENTS - products.size - existences.size
                check(remaining > 0) { "Se alcanzó el límite de 30 000 documentos por comparación. No se generó un resultado parcial." }
                val size = minOf(PAGE_SIZE, remaining)
                var query = db.collection(collection).orderBy(FieldPath.documentId()).limit(size.toLong())
                cursor?.let { query = query.startAfter(it) }
                // SERVER prevents silently comparing an incomplete offline cache against a complete Excel.
                val docs = query.get(Source.SERVER).inventoryAwait().documents
                currentCoroutineContext().ensureActive()
                check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "La sesión cambió. Vuelve a iniciar sesión." }
                docs.forEach { doc ->
                    if (collection == "productos") products += ComparedProduct(doc.id, doc.text("codigo"), doc.text("nombre"),
                        doc.integer("stock"), doc.integer("cantidadRestante"), doc.get("actualizadoEn")?.toString(),
                        doc.text("categoriaId"), doc.text("marcaId"), doc.text("descripcion"),
                        listOf("categoriaId", "marcaId", "descripcion").all { doc.get(it) == null || doc.get(it) is String })
                    else existences += ComparedExistence(doc.id, doc.text("productoId"), doc.text("codigoProducto"),
                        doc.text("codigo"), doc.integer("cantidad"), doc.text("estado"), doc.get("actualizadoEn")?.toString())
                }
                onProgress(ComparisonProgress(collection, products.size, existences.size))
                if (docs.size < size) break
                cursor = docs.last()
            }
        }
        // Recheck account state before showing the result, including a block during the scan.
        check(FirestoreAccess.requireModule(AppModule.PRODUCTS) == uid) { "La sesión cambió. Vuelve a iniciar sesión." }
        return InventoryReadSnapshot(products.toList(), existences.toList(), started, System.currentTimeMillis())
    }

    private fun DocumentSnapshot.text(field: String) = get(field) as? String
    private fun DocumentSnapshot.integer(field: String) = get(field) as? Long

    companion object {
        const val PAGE_SIZE = 500
        const val MAX_DOCUMENTS = 30_000
    }
}
