package com.example.app_clisa.data

import java.io.Serializable
import java.util.UUID

/** Local draft only. One stable request per existence preserves the existing Firestore schema. */
data class PendingExit(
    val requestId: String, val productId: String, val productCode: String, val productName: String,
    val productStock: Long, val entryId: String, val entryCode: String, val entryStock: Long,
    val quantity: Long,
) : Serializable {
    fun product() = LiveProduct(productId, productCode, productName, productStock)
    fun entry() = StockEntry(entryId, productId, entryCode, entryStock)
}

fun addPendingExit(cart: List<PendingExit>, product: LiveProduct, entries: List<StockEntry>, quantity: Long): List<PendingExit> {
    require(quantity > 0) { "Ingresa una cantidad entera mayor que cero." }
    val reservedProduct = cart.filter { it.productId == product.id }.sumOf { it.quantity }
    require(quantity <= product.stock - reservedProduct) { "La cantidad supera el stock disponible del producto." }
    val candidates = entries.distinctBy { it.id }.filter { it.productId == product.id }
    val free = candidates.sumOf { entry ->
        (entry.quantity - cart.filter { it.entryId == entry.id }.sumOf { it.quantity }).coerceAtLeast(0)
    }
    require(quantity <= free) { "No hay existencias suficientes para la cantidad solicitada." }
    val result = cart.toMutableList()
    var remaining = quantity
    for (entry in candidates) {
        if (remaining == 0L) break
        val index = result.indexOfFirst { it.entryId == entry.id }
        val reserved = result.getOrNull(index)?.quantity ?: 0
        val take = minOf(remaining, (entry.quantity - reserved).coerceAtLeast(0))
        if (take == 0L) continue
        if (index >= 0) result[index] = result[index].copy(quantity = reserved + take)
        else result += PendingExit(UUID.randomUUID().toString(), product.id, product.code, product.name,
            product.stock, entry.id, entry.code, entry.quantity, take)
        remaining -= take
    }
    return result
}
