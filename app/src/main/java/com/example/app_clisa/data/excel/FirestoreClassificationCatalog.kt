package com.example.app_clisa.data.excel

import com.example.app_clisa.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class FirestoreClassificationCatalog(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    suspend fun read(): ClassificationCatalog {
        val uid = FirestoreAccess.requireModule(AppModule.PRODUCTS)
        suspend fun load(collection: String): List<ClassificationReference> {
            val result = mutableListOf<ClassificationReference>(); var cursor: DocumentSnapshot? = null
            repeat(10) {
                currentCoroutineContext().ensureActive(); check(FirebaseAuth.getInstance().currentUser?.uid == uid)
                var q = db.collection(collection).orderBy(FieldPath.documentId()).limit(100)
                cursor?.let { q = q.startAfter(it) }
                val docs = q.get(Source.SERVER).inventoryAwait().documents
                result += docs.map { d ->
                    val name = d.get("nombre") as? String; val active = d.get("activo") as? Boolean
                    require(!name.isNullOrBlank() && active != null) { "Catálogo $collection con esquema inválido; no se usan datos parciales." }
                    ClassificationReference(d.id, name, active)
                }
                if (docs.size < 100) return result
                cursor = docs.last()
            }
            error("Catálogo $collection supera el límite de lectura controlada; no se generan sugerencias parciales.")
        }
        val categories = load("categorias"); val brands = load("marcas")
        check(FirestoreAccess.requireModule(AppModule.PRODUCTS) == uid)
        return ClassificationCatalog(categories, brands)
    }
}
