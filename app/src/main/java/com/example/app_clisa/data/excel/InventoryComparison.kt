package com.example.app_clisa.data.excel

/** Read models retain document identity separately from commercial codes. Null quantities are errors, never zero. */
data class ComparedProduct(val id: String, val code: String?, val name: String?, val stock: Long?,
    val remaining: Long?, val version: String? = null, val categoryId: String? = null, val brandId: String? = null,
    val description: String? = null, val classificationFieldsValid: Boolean = true)
data class ComparedExistence(val id: String, val productId: String?, val productCode: String?,
    val code: String?, val quantity: Long?, val state: String?, val version: String? = null)
data class InventoryReadSnapshot(val products: List<ComparedProduct>, val existences: List<ComparedExistence>,
    val startedAtMillis: Long, val finishedAtMillis: Long, val complete: Boolean = true)
data class ProductMatch(val excel: ExcelProduct, val firestore: ComparedProduct)
data class ExistenceMatch(val productCode: String, val excel: ExcelSerial, val firestore: ComparedExistence)
data class NewExistence(val productCode: String, val productId: String?, val excel: ExcelSerial)
data class ComparisonConflict(val kind: String, val key: String, val references: List<String>)
data class StockDifference(val productId: String, val code: String, val expectedExcel: Long?,
    val stock: Long?, val remaining: Long?, val existenceTotal: Long?)
data class InventoryComparison(
    val newProducts: List<ExcelProduct>, val existingProducts: List<ProductMatch>,
    val absentProducts: List<ComparedProduct>, val newExistences: List<NewExistence>,
    val unchangedExistences: List<ExistenceMatch>, val differentQuantities: List<ExistenceMatch>,
    // Candidates for review only. Physical deletion is forbidden by the deployed rules.
    val absentExistences: List<ComparedExistence>, val conflicts: List<ComparisonConflict>,
    val stockDifferences: List<StockDifference>, val estimatedInventoryWrites: Int,
    val excludedProductsFromEstimate: Int, val sourceHasErrors: Boolean,
    val startedAtMillis: Long, val finishedAtMillis: Long,
    // Retained once for the local preview, including ambiguous documents omitted from matches.
    val inventory: InventoryReadSnapshot? = null
)

/** Pure comparison: no Firebase, no writes, no automatic decisions on ambiguous identities. */
object InventoryComparator {
    fun compare(excel: ExcelInventory, snapshot: InventoryReadSnapshot, checkCancelled: () -> Unit = {}): InventoryComparison {
        require(snapshot.complete) { "No se pueden comparar ausencias con una lectura incompleta." }
        val conflicts = mutableListOf<ComparisonConflict>()
        val unsafeCodes = mutableSetOf<String>()
        fun conflict(kind: String, key: String, refs: List<String>, codes: List<String> = emptyList()) {
            conflicts += ComparisonConflict(kind, key, refs)
            unsafeCodes += codes
        }
        fun ExcelProduct.ref() = "$sheet:$row"
        fun ExcelSerial.ref() = "$sheet:$row"
        fun key(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
        val excelByCode = excel.products.filter { key(it.code) != null }.groupBy { key(it.code)!! }
        val dbByCode = snapshot.products.filter { key(it.code) != null }.groupBy { key(it.code)!! }
        val dbById = snapshot.products.associateBy { it.id }
        val excelSerials = excel.products.flatMap { p -> p.serials.map { p to it } }
        val excelBySerial = excelSerials.groupBy { key(it.second.code) }
        val dbBySerial = snapshot.existences.groupBy { key(it.code) }
        val dbEntriesByProduct = snapshot.existences.groupBy { it.productId }

        excel.products.forEach { p ->
            checkCancelled()
            if (key(p.code) == null) conflict("Producto Excel sin código", p.ref(), listOf(p.ref()))
            if (p.code != null && p.code != key(p.code)) conflict("SKU Excel con espacios", p.code, listOf(p.ref()), listOf(p.code.trim()))
        }
        excelByCode.filterValues { it.size > 1 }.forEach { (code, rows) ->
            conflict("SKU duplicado en Excel", code, rows.map { it.ref() }, listOf(code))
        }
        dbByCode.filterValues { it.size > 1 }.forEach { (code, docs) ->
            conflict("SKU duplicado en Firestore", code, docs.map { "productos/${it.id}" }, listOf(code))
        }
        snapshot.products.forEach { p ->
            val code = key(p.code)
            if (code == null || p.code != code || p.stock == null || p.stock < 0 ||
                (p.remaining != null && p.remaining < 0)) {
                conflict("Documento de producto inconsistente", p.id, listOf("productos/${p.id}"), listOfNotNull(code))
            }
        }
        excelBySerial.filter { it.key == null || it.value.size > 1 }.forEach { (serial, rows) ->
            conflict("Serie vacía o duplicada en Excel", serial.orEmpty(), rows.map { it.second.ref() }, rows.mapNotNull { key(it.first.code) })
            if (rows.map { key(it.first.code) }.distinct().size > 1) {
                conflict("Serie asociada a diferentes productos en Excel", serial.orEmpty(), rows.map { "${it.first.code}: ${it.second.ref()}" },
                    rows.mapNotNull { key(it.first.code) })
            }
        }
        dbBySerial.filter { it.key == null || it.value.size > 1 }.forEach { (serial, docs) ->
            conflict("Serie vacía o duplicada en Firestore", serial.orEmpty(), docs.map { "existencias/${it.id}" },
                docs.flatMap { listOfNotNull(key(it.productCode), key(dbById[it.productId]?.code)) })
            if (docs.map { it.productId }.distinct().size > 1) {
                conflict("Serie asociada a diferentes productos en Firestore", serial.orEmpty(),
                    docs.map { "${it.productId}: existencias/${it.id}" },
                    docs.flatMap { listOfNotNull(key(it.productCode), key(dbById[it.productId]?.code)) })
            }
        }
        val invalidEntries = mutableSetOf<String>()
        snapshot.existences.forEach { e ->
            checkCancelled()
            val parent = dbById[e.productId]
            if (parent == null || key(e.productCode) == null || e.productCode != parent.code ||
                key(e.code) == null || e.code != key(e.code) || e.quantity == null || e.quantity < 0 ||
                (e.quantity == 0L && e.state != "SIN_STOCK") || (e.quantity > 0 && e.state != "DISPONIBLE")) {
                invalidEntries += e.id
                conflict("Existencia inconsistente (referencias, cantidad o estado)", e.id, listOf("existencias/${e.id}"),
                    listOfNotNull(key(e.productCode), key(parent?.code)))
            }
        }
        val invalidExcelRows = mutableSetOf<ExcelSerial>()
        excelSerials.forEach { (p, s) ->
            if (!s.valid || key(s.code) == null || s.code != key(s.code) || s.quantity == null || s.quantity < 0) {
                invalidExcelRows += s
                conflict("Fila Excel no válida", s.code, listOf(s.ref()), listOfNotNull(key(p.code)))
            }
        }
        val newProducts = mutableListOf<ExcelProduct>()
        val existing = mutableListOf<ProductMatch>()
        excelByCode.forEach { (code, groups) ->
            if (groups.size == 1 && dbByCode[code].orEmpty().size <= 1) {
                val product = dbByCode[code]?.singleOrNull()
                if (product == null) newProducts += groups.single() else existing += ProductMatch(groups.single(), product)
            }
        }
        val newEntries = mutableListOf<NewExistence>()
        val same = mutableListOf<ExistenceMatch>()
        val different = mutableListOf<ExistenceMatch>()
        excelSerials.forEach { (p, serial) ->
            checkCancelled()
            val code = key(p.code) ?: return@forEach
            if (excelByCode[code]?.size != 1 || dbByCode[code].orEmpty().size > 1 ||
                excelBySerial[key(serial.code)]?.size != 1 || serial in invalidExcelRows) return@forEach
            val docs = dbBySerial[key(serial.code)].orEmpty()
            val parent = dbByCode[code]?.singleOrNull()
            if (docs.isEmpty()) newEntries += NewExistence(code, parent?.id, serial)
            else if (docs.size == 1) {
                val entry = docs.single()
                if (parent == null || entry.productId != parent.id || entry.productCode != code) {
                    conflict("Serie asociada a otro producto", serial.code, listOf(serial.ref(), "existencias/${entry.id}"),
                        listOfNotNull(code, key(entry.productCode), key(dbById[entry.productId]?.code)))
                } else if (entry.id !in invalidEntries) {
                    val match = ExistenceMatch(code, serial, entry)
                    if (serial.quantity == entry.quantity) same += match else different += match
                }
            }
        }
        val stockDifferences = mutableListOf<StockDifference>()
        existing.forEach { match ->
            val p = match.firestore
            val code = key(p.code)!!
            val rows = match.excel.serials
            val expected = if (code !in unsafeCodes && (rows.isNotEmpty() || validZeroSummary(match.excel))) safeSum(rows.map { it.quantity }) else null
            if (expected == null || match.excel.reportedQuantity != expected) {
                conflict("Stock Excel no conciliable", code, listOf(match.excel.ref()), listOf(code))
            }
            val entries = dbEntriesByProduct[p.id].orEmpty()
            val total = if (entries.any { it.id in invalidEntries || dbBySerial[key(it.code)].orEmpty().size != 1 }) null
                else safeSum(entries.map { it.quantity })
            if (expected == null || p.stock != expected || p.remaining != expected || total == null || p.stock != total) {
                stockDifferences += StockDifference(p.id, code, expected, p.stock, p.remaining, total)
            }
        }
        val matchedIds = existing.map { it.firestore.id }.toSet()
        snapshot.products.filter { it.id !in matchedIds }.forEach { p ->
            val entries = dbEntriesByProduct[p.id].orEmpty()
            val total = if (entries.any { it.id in invalidEntries || dbBySerial[key(it.code)].orEmpty().size != 1 }) null
                else safeSum(entries.map { it.quantity })
            // No Excel total can be attributed to an absent/ambiguous product. Still expose legacy-counter discrepancies.
            if (p.stock == null || p.remaining != p.stock || total == null || total != p.stock) {
                stockDifferences += StockDifference(p.id, p.code.orEmpty(), null, p.stock, p.remaining, total)
            }
        }
        newProducts.forEach { p ->
            val total = safeSum(p.serials.map { it.quantity })
            if ((p.serials.isEmpty() && !validZeroSummary(p)) || total == null || total != p.reportedQuantity) {
                conflict("Stock de producto nuevo no conciliable", p.code.orEmpty(), listOf(p.ref()), listOfNotNull(key(p.code)))
            }
        }
        // Absence is a factual key-set difference, not authorization to delete. No absence is included in the estimate.
        val absentProducts = snapshot.products.filter { key(it.code) != null && key(it.code) !in excelByCode }
        val absentEntries = snapshot.existences.filter { key(it.code) != null && key(it.code) !in excelBySerial }
        // Retained absences never change the Excel reference total or imply deletion.
        val excludedCodes = unsafeCodes
        val changedProducts = existing.filter { m ->
            val code = key(m.excel.code)!!
            code !in excludedCodes && m.firestore.stock != safeSum(m.excel.serials.map { it.quantity })
        }.map { it.firestore.id }.toSet()
        val writes = newProducts.count { key(it.code) !in excludedCodes } + changedProducts.size +
            newEntries.count { it.productCode !in excludedCodes } + different.count { it.productCode !in excludedCodes }
        return InventoryComparison(newProducts, existing, absentProducts, newEntries, same, different, absentEntries,
            conflicts, stockDifferences, writes, excludedCodes.size, excel.errorCount > 0,
            snapshot.startedAtMillis, snapshot.finishedAtMillis, snapshot)
    }

    private fun validZeroSummary(p: ExcelProduct) = p.serials.isEmpty() && p.reportedQuantity == 0L &&
        (p.reportedSerialCount == null || p.reportedSerialCount == 0)

    private fun safeSum(values: List<Long?>): Long? {
        var total = 0L
        for (value in values) {
            if (value == null || value < 0 || Long.MAX_VALUE - total < value) return null
            total += value
        }
        return total
    }
}
