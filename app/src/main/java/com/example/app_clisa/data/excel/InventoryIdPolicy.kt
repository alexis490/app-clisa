package com.example.app_clisa.data.excel

data class InventoryIdentity(val documentId: String?, val existing: Boolean, val blockers: List<String>, val references: List<String>) {
    val valid: Boolean get() = documentId != null && blockers.isEmpty()
}

/** Versioned exact identities, not sanitized SKUs. The retained complete baseline is the only lookup source. */
class InventoryIdPolicy(private val snapshot: InventoryReadSnapshot?) {
    private val productsBySku = snapshot?.products.orEmpty().groupBy { it.code?.trim() }
    private val productsById = snapshot?.products.orEmpty().groupBy { it.id }
    private val entriesBySerial = snapshot?.existences.orEmpty().groupBy { it.code?.trim() }
    private val entriesById = snapshot?.existences.orEmpty().groupBy { it.id }
    private val unidentifiedProducts = snapshot?.products.orEmpty().any { it.code.isNullOrBlank() }
    private val unidentifiedSeries = snapshot?.existences.orEmpty().any { it.code.isNullOrBlank() }
    private fun validId(id: String) = id.isNotBlank() && '/' !in id && id !in setOf(".", "..") &&
        !id.matches(Regex("__.*__")) && id.toByteArray(Charsets.UTF_8).size <= 1500
    private fun invalid(reason: String, references: List<String> = emptyList()) = InventoryIdentity(null, false, listOf(reason), references.sorted())
    private fun exactCode(code: String) = code.isNotBlank() && code == code.trim()

    fun product(sku: String): InventoryIdentity {
        if (snapshot?.complete != true) return invalid("COMPLETE_BASELINE_REQUIRED")
        if (!exactCode(sku)) return invalid("INVALID_PRODUCT_CODE")
        val matches = productsBySku[sku].orEmpty()
        if (matches.size > 1 || matches.any { it.code != sku }) return invalid("AMBIGUOUS_PRODUCT_CODE", matches.map { "productos/${it.id}" })
        matches.singleOrNull()?.let {
            if (!validId(it.id) || productsById[it.id]?.size != 1) return invalid("AMBIGUOUS_PRODUCT_DOCUMENT_ID")
            return InventoryIdentity(it.id, true, emptyList(), listOf("productos/${it.id}"))
        }
        if (unidentifiedProducts) return invalid("UNIDENTIFIED_PRODUCTS_IN_BASELINE")
        val id = newProductId(sku)
        if (productsById[id].orEmpty().isNotEmpty()) return invalid("PRODUCT_ID_COLLISION", listOf("productos/$id"))
        return InventoryIdentity(id, false, emptyList(), emptyList())
    }

    fun existence(sku: String, serial: String): InventoryIdentity {
        val parent = product(sku)
        if (!parent.valid) return parent
        if (!exactCode(serial)) return invalid("INVALID_SERIAL_CODE")
        val matches = entriesBySerial[serial].orEmpty()
        if (matches.size > 1) return invalid("AMBIGUOUS_SERIAL_CODE", matches.map { "existencias/${it.id}" })
        matches.singleOrNull()?.let { e ->
            if (!validId(e.id) || entriesById[e.id]?.size != 1) return invalid("AMBIGUOUS_EXISTENCE_DOCUMENT_ID")
            if (e.code != serial || e.productId != parent.documentId || e.productCode != sku)
                return invalid("SERIAL_ASSIGNED_TO_OTHER_PRODUCT", listOf("existencias/${e.id}"))
            return InventoryIdentity(e.id, true, emptyList(), listOf("existencias/${e.id}"))
        }
        if (unidentifiedSeries) return invalid("UNIDENTIFIED_SERIES_IN_BASELINE")
        val id = newExistenceId(parent.documentId!!, serial)
        if (entriesById[id].orEmpty().isNotEmpty()) return invalid("EXISTENCE_ID_COLLISION", listOf("existencias/$id"))
        return InventoryIdentity(id, false, emptyList(), emptyList())
    }

    companion object {
        fun newProductId(sku: String): String {
            require(sku.isNotBlank() && sku == sku.trim())
            return "p_v1_${SyncCanonical.hash(listOf("CLISA_PRODUCT_ID_V1", sku))}"
        }
        fun newExistenceId(productId: String, serial: String): String {
            require(productId.isNotBlank() && '/' !in productId && serial.isNotBlank() && serial == serial.trim())
            return "e_v1_${SyncCanonical.hash(listOf("CLISA_EXISTENCE_ID_V1", productId, serial))}"
        }
    }
}
