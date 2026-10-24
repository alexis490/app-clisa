package com.example.app_clisa.data.excel

enum class PreviewGroup(val label: String) {
    NEW_PRODUCTS("Productos nuevos"), EXISTING_PRODUCTS("Productos existentes"), NEW_EXISTENCES("Existencias nuevas"),
    DIFFERENT_QUANTITIES("Cantidades diferentes"), ABSENT_EXISTENCES("Ausencias conservadas"), ABSENT_PRODUCTS("Productos ausentes"),
    UNCHANGED("Existencias sin cambios"), SERIAL_CONFLICTS("Conflictos de series"), STOCK("Inconsistencias de stock"),
    CONFLICTS("Todos los conflictos")
}

data class PreviewFact(val productCode: String?, val productId: String?, val serial: String?, val existenceId: String?,
    val oldQuantity: Long?, val excelQuantity: Long?, val reference: String)
data class PreviewDetail(val id: String, val title: String, val group: PreviewGroup,
    val productCode: String? = null, val productId: String? = null, val serial: String? = null,
    val existenceId: String? = null, val oldQuantity: Long? = null, val excelQuantity: Long? = null,
    val stock: Long? = null, val remaining: Long? = null, val expectedStock: Long? = null,
    val note: String = "", val references: List<String> = emptyList(), val facts: List<PreviewFact> = emptyList()) {
    fun matches(query: String): Boolean {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val text = listOfNotNull(title, productCode, productId, serial, existenceId, note).joinToString(" ") +
            references.joinToString(" ") + facts.joinToString(" ") { "${it.productCode.orEmpty()} ${it.productId.orEmpty()} ${it.serial.orEmpty()} ${it.existenceId.orEmpty()}" }
        return words.all { text.contains(it, ignoreCase = true) }
    }
}
data class PreviewProduct(val id: String, val name: String, val code: String?, val documentId: String?, val stock: Long?,
    val remaining: Long?, val expectedStock: Long?, val group: PreviewGroup, val details: List<PreviewDetail>)
data class InventoryPreview(val groups: Map<PreviewGroup, List<PreviewDetail>>, val products: List<PreviewProduct>)

/** Presentation projection only. It has no repository dependency and cannot refresh or synchronize inventory. */
object InventoryPreviewBuilder {
    fun build(result: InventoryComparison, excel: ExcelInventory): InventoryPreview {
        val groups = PreviewGroup.entries.associateWith { mutableListOf<PreviewDetail>() }
        val readProducts = result.inventory?.products.orEmpty().associateBy { it.id }
        val expectedById = result.stockDifferences.associateBy { it.productId }
        val entriesBySerial = result.inventory?.existences.orEmpty().groupBy { it.code }
        val facts = mutableMapOf<String, PreviewFact>()
        result.inventory?.products.orEmpty().forEach { p ->
            val ref = "productos/${p.id}"
            facts[ref] = PreviewFact(p.code, p.id, null, null, null, null, ref)
        }
        result.inventory?.existences.orEmpty().forEach { e ->
            val ref = "existencias/${e.id}"
            facts[ref] = PreviewFact(e.productCode, e.productId, e.code, e.id, e.quantity, null, ref)
        }
        excel.products.forEach { p ->
            val parentId = result.existingProducts.singleOrNull { it.excel.sheet == p.sheet && it.excel.row == p.row }?.firestore?.id
            val ref = "${p.sheet}:${p.row}"
            facts[ref] = PreviewFact(p.code, parentId, null, null, null, null, ref)
            p.serials.forEach { s ->
                val serialRef = "${s.sheet}:${s.row}"
                facts[serialRef] = PreviewFact(p.code, parentId, s.code, null, null, s.quantity, serialRef)
            }
        }
        val relatedFacts = mutableMapOf<String, List<PreviewFact>>()
        val entriesByProduct = result.inventory?.existences.orEmpty().groupBy { it.productId }
        result.inventory?.products.orEmpty().forEach { p ->
            val ref = "productos/${p.id}"
            relatedFacts[ref] = listOfNotNull(facts[ref]) + entriesByProduct[p.id].orEmpty().mapNotNull { facts["existencias/${it.id}"] }
        }
        excel.products.forEach { p ->
            val ref = "${p.sheet}:${p.row}"
            relatedFacts[ref] = listOfNotNull(facts[ref]) + p.serials.mapNotNull { facts["${it.sheet}:${it.row}"] }
        }
        fun expected(p: ComparedProduct): Long? = expectedById[p.id]?.expectedExcel
            ?: p.stock.takeIf { p.id !in expectedById }
        result.newProducts.forEach { p -> groups.getValue(PreviewGroup.NEW_PRODUCTS) += PreviewDetail(
            "new-product:${p.sheet}:${p.row}", p.name, PreviewGroup.NEW_PRODUCTS, p.code,
            note = "Producto identificado como nuevo. Su ID todavía no se ha asignado; puede tener conflictos pendientes.",
            references = listOf("${p.sheet}:${p.row}"),
            facts = p.serials.mapNotNull { facts["${it.sheet}:${it.row}"] }) }
        result.existingProducts.forEach { m -> groups.getValue(PreviewGroup.EXISTING_PRODUCTS) += PreviewDetail(
            "product:${m.firestore.id}", m.excel.name, PreviewGroup.EXISTING_PRODUCTS, m.firestore.code, m.firestore.id,
            stock = m.firestore.stock, remaining = m.firestore.remaining, expectedStock = expected(m.firestore),
            facts = m.excel.serials.mapNotNull { s -> facts["${s.sheet}:${s.row}"]?.let { fact ->
                val entry = entriesBySerial[s.code]?.singleOrNull()?.takeIf { it.productId == m.firestore.id }
                fact.copy(existenceId = entry?.id, oldQuantity = entry?.quantity)
            } }) }
        result.newExistences.forEachIndexed { i, e -> groups.getValue(PreviewGroup.NEW_EXISTENCES) += PreviewDetail(
            "new-entry:$i", "Existencia nueva", PreviewGroup.NEW_EXISTENCES, e.productCode, e.productId, e.excel.code,
            excelQuantity = e.excel.quantity, note = "Sin documento de existencia previo. Su creación no está autorizada en esta etapa.",
            references = listOf("${e.excel.sheet}:${e.excel.row}")) }
        fun matches(rows: List<ExistenceMatch>, group: PreviewGroup) {
            rows.forEach { m -> groups.getValue(group) += PreviewDetail("${group.name}:${m.firestore.id}", group.label, group,
                m.productCode, m.firestore.productId, m.excel.code, m.firestore.id, m.firestore.quantity, m.excel.quantity,
                references = listOf("existencias/${m.firestore.id}", "${m.excel.sheet}:${m.excel.row}")) }
        }
        matches(result.differentQuantities, PreviewGroup.DIFFERENT_QUANTITIES)
        matches(result.unchangedExistences, PreviewGroup.UNCHANGED)
        result.absentExistences.forEach { e -> groups.getValue(PreviewGroup.ABSENT_EXISTENCES) += PreviewDetail(
            "absent-entry:${e.id}", "Serie ausente del Excel", PreviewGroup.ABSENT_EXISTENCES, e.productCode, e.productId,
            e.code, e.id, oldQuantity = e.quantity,
            note = "No aparece en las filas interpretadas. Se conserva el documento y su cantidad sin cambios; requiere revisión, sin eliminación automática.",
            references = listOf("existencias/${e.id}")) }
        result.absentProducts.forEach { p -> groups.getValue(PreviewGroup.ABSENT_PRODUCTS) += PreviewDetail(
            "absent-product:${p.id}", p.name ?: "Producto sin nombre", PreviewGroup.ABSENT_PRODUCTS, p.code, p.id,
            stock = p.stock, remaining = p.remaining,
            note = "Ausente por código. No equivale a stock cero ni a una eliminación aprobada.") }
        result.stockDifferences.forEach { s -> groups.getValue(PreviewGroup.STOCK) += PreviewDetail(
            "stock:${s.productId}", readProducts[s.productId]?.name ?: "Diferencia de stock", PreviewGroup.STOCK, s.code, s.productId,
            stock = s.stock, remaining = s.remaining, expectedStock = s.expectedExcel,
            note = "Suma de cantidades de existencias: ${s.existenceTotal ?: "no fiable"}. Los contadores no se consideran equivalentes.") }
        val conflictCodes = mutableSetOf<String>()
        result.conflicts.forEachIndexed { i, c ->
            val associated = c.references.flatMap { ref ->
                val key = if (ref in facts) ref else ref.substringAfter(": ", ref)
                relatedFacts[key] ?: listOfNotNull(facts[key])
            }.distinct()
            conflictCodes += associated.mapNotNull { it.productCode }
            if (c.kind.startsWith("SKU ")) conflictCodes += c.key
            val row = PreviewDetail("conflict:$i", c.kind, PreviewGroup.CONFLICTS,
                note = "Clave: ${c.key}. Pendiente de resolución; no se selecciona automáticamente ninguna coincidencia.",
                references = c.references, facts = associated)
            groups.getValue(PreviewGroup.CONFLICTS) += row
            if (c.kind.startsWith("Serie ")) groups.getValue(PreviewGroup.SERIAL_CONFLICTS) += row.copy(group = PreviewGroup.SERIAL_CONFLICTS)
        }
        val newExpected = result.newProducts.associate { p ->
            val valid = p.serials.isNotEmpty() && p.serials.all { it.valid && it.quantity != null && it.quantity >= 0 }
            val total = if (valid) sumSafely(p.serials.map { it.quantity!! }) else null
            "${p.sheet}:${p.row}" to total?.takeIf { it == p.reportedQuantity && p.code !in conflictCodes }
        }
        groups.getValue(PreviewGroup.NEW_PRODUCTS).let { rows ->
            rows.indices.forEach { i -> rows[i] = rows[i].copy(expectedStock = newExpected[rows[i].references.single()]) }
        }
        // Index details once. Expanding cards never scans Firestore or the full Excel again.
        val detailRows = groups.filterKeys { it != PreviewGroup.SERIAL_CONFLICTS }.values.flatten()
        val detailsById = detailRows.filter { it.productId != null }.groupBy { it.productId }
        val detailsByCode = detailRows.filter { it.productId == null && it.productCode != null }.groupBy { it.productCode }
        val products = mutableListOf<PreviewProduct>()
        result.existingProducts.forEach { m ->
            val p = m.firestore
            products += PreviewProduct("product:${p.id}", m.excel.name, p.code, p.id, p.stock, p.remaining,
                expected(p), PreviewGroup.EXISTING_PRODUCTS, detailsById[p.id].orEmpty() + detailsByCode[p.code].orEmpty())
        }
        result.newProducts.forEach { p ->
            products += PreviewProduct("new:${p.sheet}:${p.row}", p.name, p.code, null, null, null,
                newExpected["${p.sheet}:${p.row}"], PreviewGroup.NEW_PRODUCTS, detailsByCode[p.code].orEmpty())
        }
        result.absentProducts.forEach { p -> products += PreviewProduct("product:${p.id}", p.name ?: "Producto sin nombre",
            p.code, p.id, p.stock, p.remaining, null, PreviewGroup.ABSENT_PRODUCTS, detailsById[p.id].orEmpty()) }
        // Ambiguous SKU groups/documents remain inspectable in Conflicts, with every original reference and ID.
        return InventoryPreview(groups.mapValues { it.value.toList() }, products)
    }

    private fun sumSafely(values: List<Long>): Long? {
        var total = 0L
        for (n in values) {
            if (n < 0 || Long.MAX_VALUE - total < n) return null
            total += n
        }
        return total
    }
}
