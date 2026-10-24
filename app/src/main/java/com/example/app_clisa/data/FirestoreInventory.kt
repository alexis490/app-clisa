package com.example.app_clisa.data

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class StockEntry(val id: String, val productId: String, val code: String, val quantity: Long)
data class LiveProduct(val id: String, val code: String, val name: String, val stock: Long)
data class LiveStore(val id: String, val name: String, val active: Boolean)
data class LiveReceiver(val id: String, val name: String, val storeId: String)
data class CodeMatch(val product: LiveProduct, val entry: StockEntry? = null)
data class EntryPage(val entries: List<StockEntry>, val cursor: DocumentSnapshot?, val hasMore: Boolean)
data class InventoryData(
    val receivers: List<LiveReceiver> = emptyList(),
    val stores: List<LiveStore> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Uses the existing one-existence-per-record schema without changing security rules. */
class FirestoreInventory {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /** Refresh only on explicit retry; reference data has no permanent listeners. */
    suspend fun loadReferenceData(refreshCredentials: Boolean = false): InventoryData {
        val user = auth.currentUser ?: error("Inicia sesión para acceder a Firestore.")
        user.getIdToken(refreshCredentials).inventoryAwait()
        val stores = db.collection("tiendas").whereIn(FieldPath.documentId(), listOf("clisa", "toqen"))
            .get(Source.SERVER).inventoryAwait().documents.map {
                LiveStore(it.id, it.getString("nombre") ?: it.id.uppercase(), it.getString("estado") == "ACTIVA")
            }
        val documents = try {
            db.collection("receptores").whereEqualTo("activo", true).whereEqualTo("tiendaId", "toqen")
                .get(Source.SERVER).inventoryAwait().documents
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                throw IllegalStateException("La sesión se conectó a Firebase, pero las reglas no autorizan leer receptores. Revisa los permisos publicados de esta colección y de tu cuenta.", e)
            throw e
        }
        val receivers = documents.mapNotNull { document ->
            val name = document.getString("nombre")?.trim()?.takeIf { it.isNotEmpty() }
            name?.let { LiveReceiver(document.id, it, "toqen") }
        }.sortedWith(compareBy<LiveReceiver> { it.name.lowercase() }.thenBy { it.id })
        return InventoryData(receivers = receivers, stores = stores, loading = false)
    }
    suspend fun resolveCode(raw: String): CodeMatch? {
        val code = raw.trim()
        require(code.isNotEmpty()) { "Ingresa el código del producto." }
        val documents = db.collection("productos").whereEqualTo("codigo", code)
            .limit(2).get(Source.SERVER).inventoryAwait().documents
        check(documents.size <= 1) { "El código corresponde a más de un producto. Revisa el inventario." }
        if (documents.isNotEmpty()) return documents.single().liveProduct()?.let { CodeMatch(it) }
        val entries = db.collection("existencias").whereEqualTo("codigo", code)
            .limit(2).get(Source.SERVER).inventoryAwait().documents
        check(entries.size <= 1) { "El código corresponde a más de una existencia. Revisa el inventario." }
        val entry = entries.singleOrNull()?.stockEntry() ?: return null
        val product = db.collection("productos").document(entry.productId).get(Source.SERVER)
            .inventoryAwait().liveProduct() ?: return null
        return CodeMatch(product, entry)
    }

    // All existing inventory belongs to CLISA (confirmed by the owner); no tiendaId field exists here.
    suspend fun availableEntries(productId: String, cursor: DocumentSnapshot? = null, needed: Long = 25): EntryPage {
        val pageSize = needed.coerceIn(1, 25)
        // Equality filters can merge the automatic single-field indexes. Keep the
        // default document-ID order for a stable cursor; stockEntry rejects zero stock.
        var query = db.collection("existencias").whereEqualTo("productoId", productId)
            .whereEqualTo("estado", "DISPONIBLE").limit(pageSize)
        if (cursor != null) query = query.startAfter(cursor)
        val documents = query.get(Source.SERVER).inventoryAwait().documents
        return EntryPage(documents.mapNotNull { it.stockEntry() }, documents.lastOrNull(), documents.size.toLong() == pageSize)
    }

    private fun DocumentSnapshot.stockEntry(): StockEntry? {
        val productId = getString("productoId")?.takeIf { it.isNotBlank() } ?: return null
        val code = getString("codigo")?.takeIf { it.isNotBlank() } ?: return null
        val quantity = getLong("cantidad") ?: 0
        return if (getString("estado") == "DISPONIBLE" && quantity > 0) StockEntry(id, productId, code, quantity) else null
    }

    private fun DocumentSnapshot.liveProduct(): LiveProduct? = if (getString("estado") == "ACTIVO")
        LiveProduct(id, getString("codigo") ?: id, getString("nombre") ?: id, getLong("stock") ?: 0) else null

    suspend fun save(recordId: String, product: LiveProduct, entry: StockEntry, quantity: Long, receiver: LiveReceiver, payment: String) {
        require(quantity > 0 && receiver.name.isNotBlank() && receiver.storeId == "toqen") { "Revisa la cantidad y el receptor seleccionado." }
        require(payment in listOf("Pagado", "Pendiente")) { "Selecciona el estado de pago." }
        val user = auth.currentUser ?: error("Vuelve a iniciar sesión.")
        val claims = user.getIdToken(false).inventoryAwait().claims
        val recordRef = db.collection("registros").document(recordId)
        val productRef = db.collection("productos").document(product.id)
        val entryRef = db.collection("existencias").document(entry.id)
        db.runTransaction { transaction ->
            val existing = transaction.get(recordRef)
            if (existing.exists()) {
                check(existing.getString("registradoPorId") == user.uid && existing.getString("productoId") == product.id &&
                    existing.getString("existenciaId") == entry.id && existing.getLong("cantidad") == quantity &&
                    existing.getString("recibidoPor") == receiver.name && existing.getString("estadoPago") == payment) { "Esta solicitud ya se guardó con otros datos." }
            } else {
                // All reads precede writes. Stock and names are revalidated on each transaction retry.
                val currentProduct = transaction.get(productRef)
                val currentEntry = transaction.get(entryRef)
                val origin = transaction.get(db.collection("tiendas").document("clisa"))
                val destination = transaction.get(db.collection("tiendas").document("toqen"))
                val profile = transaction.get(db.collection("usuarios").document(user.uid))
                val currentReceiver = transaction.get(db.collection("receptores").document(receiver.id))
                check(currentReceiver.getBoolean("activo") == true && currentReceiver.getString("tiendaId") == "toqen" &&
                    currentReceiver.getString("nombre")?.trim() == receiver.name) { "El receptor cambió o ya no está activo en TOQEN. Selecciónalo nuevamente." }
                AccessPolicy.requireAccess(claims, profile.getString("role"), profile.getString("estado"), AppModule.REGISTER)
                check(origin.getString("estado") == "ACTIVA" && destination.getString("estado") == "ACTIVA") { "CLISA y TOQEN deben estar activas en Firestore." }
                check(currentProduct.getString("estado") == "ACTIVO" && currentEntry.getString("estado") == "DISPONIBLE" &&
                    currentEntry.getString("productoId") == product.id && currentEntry.getString("codigo") == entry.code) { "La existencia cambió o ya no está disponible. Recarga el inventario." }
                val plan = StockDeduction.calculate(currentProduct.getLong("stock") ?: 0, currentEntry.getLong("cantidad") ?: 0, quantity)
                val record = mapOf(
                    "registroId" to recordId, "tipo" to "SALIDA", "estado" to "COMPLETADO",
                    "productoId" to product.id, "codigoProducto" to (currentProduct.getString("codigo") ?: product.id),
                    "nombreProducto" to (currentProduct.getString("nombre") ?: error("El producto no tiene nombre.")),
                    "existenciaId" to entry.id, "codigoExistencia" to entry.code, "cantidad" to quantity,
                    "tiendaOrigenId" to "clisa", "tiendaOrigenNombre" to (origin.getString("nombre") ?: error("CLISA no tiene nombre.")),
                    "tiendaDestinoId" to "toqen", "tiendaDestinoNombre" to (destination.getString("nombre") ?: error("TOQEN no tiene nombre.")),
                    "registradoPorId" to user.uid, "registradoPorNombre" to (profile.getString("displayName") ?: user.displayName ?: "Usuario"),
                    "registradoPorEmail" to (user.email ?: error("Tu cuenta no tiene correo.")),
                    "recibidoPor" to receiver.name, "fechaHora" to FieldValue.serverTimestamp(),
                    "estadoPago" to payment,
                ) + TransferFormalization.initialFields()
                transaction.set(recordRef, record)
                transaction.update(productRef, mapOf("stock" to plan.productStock, "ultimaSalidaId" to recordId, "actualizadoEn" to FieldValue.serverTimestamp()))
                transaction.update(entryRef, mapOf("cantidad" to plan.entryStock, "estado" to plan.entryState, "ultimaSalidaId" to recordId, "actualizadoEn" to FieldValue.serverTimestamp()))
            }
            Unit
        }.inventoryAwait()
    }
}

internal suspend fun <T> Task<T>.inventoryAwait(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

fun inventoryError(error: Exception): String = when (error) {
    is FirebaseFirestoreException -> when (error.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Firestore rechazó la operación. Revisa el rol de acceso y las reglas publicadas."
        FirebaseFirestoreException.Code.UNAVAILABLE -> "No hay conexión con Firestore. La salida no se confirmó; vuelve a intentar cuando tengas conexión."
        FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> "Firestore alcanzó una cuota o límite de recursos. Revisa el uso del proyecto antes de volver a intentar."
        FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
            if (error.message?.contains("index", ignoreCase = true) == true)
                "Firestore requiere un índice para esta consulta. Revisa los índices del proyecto en Firebase."
            else "Firestore no puede ejecutar la operación con su configuración actual. Revisa el estado de la base de datos."
        else -> "No se pudo completar la operación en Firestore. Vuelve a intentar."
    }
    is IllegalStateException, is IllegalArgumentException -> error.message ?: "Revisa los datos de la salida."
    else -> "No se pudo conectar con Firestore. Revisa tu conexión."
}
