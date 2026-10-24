package com.example.app_clisa.data.excel

/** Offline interchange only. No transport, signing authority, credentials or write action in Android. */
object SyncExecutionContract {
    const val SCHEMA_VERSION = 1

    fun value(plan: SyncPlan, excel: ExcelInventory, comparison: InventoryComparison, requesterUid: String): Map<String, Any?> {
        require(requesterUid.isNotBlank() && '/' !in requesterUid)
        require(plan.schemaVersion == 4 && plan.fileSha256 != null)
        val baseline = requireNotNull(comparison.inventory)
        require(plan.baselineFingerprint == SyncPlanIdentity.inventoryFingerprint(baseline))
        val completeness = ExcelCompletenessValidator.assess(excel, plan.fileSha256)
        return mapOf(
            "schemaVersion" to SCHEMA_VERSION, "requester" to mapOf("uid" to requesterUid),
            "plan" to mapOf("schemaVersion" to plan.schemaVersion, "inventoryPolicy" to "EXCEL_REFERENCE_V1", "planId" to plan.planId,
                "fileSha256" to plan.fileSha256, "fileName" to plan.sourceFileName, "baselineFingerprint" to plan.baselineFingerprint,
                "observedAtMillis" to plan.observedAtMillis, "productionExecutable" to false,
                "temporalIdentity" to plan.temporal?.identityValue(),
                "temporal" to plan.temporal?.let { mapOf("declaredMillis" to it.declaration.epochMillis(),
                    "date" to it.declaration.date?.toString(), "time" to it.declaration.time?.toString(),
                    "zone" to "America/Lima", "uncertain" to it.declaration.uncertain, "blockers" to it.blockers) },
                "checks" to plan.checks.map { mapOf("code" to it.code, "satisfied" to it.satisfied, "explanation" to it.explanation) },
                "operations" to plan.operations.map { o -> mapOf("operationId" to o.operationId, "type" to o.type.name,
                    "collection" to o.collection, "documentId" to o.documentId, "path" to o.path, "sku" to o.sku,
                    "serial" to o.serial, "productId" to o.productId, "before" to o.before, "expected" to o.expected,
                    "reason" to o.reason, "validation" to o.validation.name, "risk" to o.risk.name,
                    "blockers" to o.blockers, "references" to o.references, "atomicGroup" to o.atomicGroup, "observedVersion" to o.observedVersion) }),
            "source" to mapOf("errorCount" to excel.errorCount,
                "structure" to excel.structure?.let { mapOf("xlsxValidated" to it.xlsxValidated, "sheets" to it.sheets,
                    "sheetsWithHeaders" to it.sheetsWithHeaders, "uninterpretedRows" to it.uninterpretedRows, "preambleRows" to it.preambleRows) },
                "issues" to excel.issues.map { mapOf("kind" to it.kind.name, "sheet" to it.sheet, "row" to it.row,
                    "productCode" to it.productCode, "productName" to it.productName, "serialCode" to it.serialCode, "message" to it.message) },
                "completeness" to mapOf("state" to completeness.state.name, "technicalValid" to completeness.technicalValid,
                    "manifestDigest" to completeness.manifestDigest, "reasons" to completeness.reasons),
                "products" to excel.products.map { p -> mapOf("code" to p.code, "name" to p.name,
                    "reportedQuantity" to p.reportedQuantity, "sheet" to p.sheet, "row" to p.row,
                    "serials" to p.serials.map { s -> mapOf("code" to s.code, "quantity" to s.quantity,
                        "sheet" to s.sheet, "row" to s.row, "valid" to s.valid) }) }),
            "comparison" to mapOf("complete" to baseline.complete,
                "classification" to mapOf("newProductCodes" to comparison.newProducts.map { it.code },
                    "existingProductIds" to comparison.existingProducts.map { it.firestore.id },
                    "absentProductIds" to comparison.absentProducts.map { it.id },
                    "newExistences" to comparison.newExistences.map { mapOf("sku" to it.productCode,
                        "productId" to it.productId, "serial" to it.excel.code, "quantity" to it.excel.quantity) },
                    "unchangedExistenceIds" to comparison.unchangedExistences.map { it.firestore.id },
                    "differentQuantityIds" to comparison.differentQuantities.map { it.firestore.id },
                    "absentExistenceIds" to comparison.absentExistences.map { it.id },
                    "stockDifferences" to comparison.stockDifferences.map { mapOf("productId" to it.productId,
                        "sku" to it.code, "expectedExcel" to it.expectedExcel, "stock" to it.stock,
                        "cantidadRestante" to it.remaining, "existenceTotal" to it.existenceTotal) }),
                "conflicts" to comparison.conflicts.map { mapOf("kind" to it.kind, "key" to it.key, "references" to it.references) },
                "products" to baseline.products.map { p -> mapOf("id" to p.id, "code" to p.code, "name" to p.name,
                    "stock" to p.stock, "remaining" to p.remaining, "version" to p.version) },
                "existences" to baseline.existences.map { e -> mapOf("id" to e.id, "productId" to e.productId,
                    "productCode" to e.productCode, "code" to e.code, "quantity" to e.quantity, "state" to e.state, "version" to e.version) })
        )
    }

    fun json(plan: SyncPlan, excel: ExcelInventory, comparison: InventoryComparison, requesterUid: String): String =
        jsonValue(value(plan, excel, comparison, requesterUid))

    internal fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"'); value.forEach { ch -> when (ch) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (ch.code < 32) append("\\u%04x".format(ch.code)) else append(ch)
            } }; append('"')
        }
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Long -> { require(value in -9_007_199_254_740_991L..9_007_199_254_740_991L); value.toString() }
        is Map<*, *> -> value.entries.sortedBy { it.key as String }.joinToString(",", "{", "}") { jsonValue(it.key) + ":" + jsonValue(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { jsonValue(it) }
        else -> error("Unsupported wire value")
    }
}
