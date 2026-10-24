package com.example.app_clisa.data.excel

/** Step 4A only. Pure data -> plan. No Firebase imports, repository, network or apply/commit method. */
object InventoryDryRunPlanner {
    fun generate(result: InventoryComparison, evidence: ReportReadEvidence = ReportReadEvidence()): SyncPlan {
        val snapshot = result.inventory
        val idPolicy = InventoryIdPolicy(snapshot)
        val products = snapshot?.products.orEmpty().groupBy { it.id }
        val entries = snapshot?.existences.orEmpty().groupBy { it.id }
        val fileHash = evidence.fileSha256?.lowercase(java.util.Locale.ROOT)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        fun validId(id: String) = id.isNotBlank() && '/' !in id && id !in setOf(".", "..") &&
            !id.matches(Regex("__.*__")) && id.toByteArray(Charsets.UTF_8).size <= 1500
        val identitiesValid = snapshot != null && products.values.all { it.size == 1 } && entries.values.all { it.size == 1 } &&
            snapshot.products.all { p -> validId(p.id) && !p.code.isNullOrBlank() && p.code == p.code.trim() && p.stock != null && p.stock >= 0 } &&
            snapshot.products.groupBy { it.code }.values.all { it.size == 1 } &&
            snapshot.existences.all { e ->
                val p = products[e.productId]?.singleOrNull()
                validId(e.id) && p != null && e.productCode == p.code && !e.code.isNullOrBlank() && e.code == e.code.trim() &&
                    e.quantity != null && e.quantity >= 0 && e.state == if (e.quantity == 0L) "SIN_STOCK" else "DISPONIBLE"
            } && snapshot.existences.groupBy { it.code }.values.all { it.size == 1 }
        val checks = listOf(
            PlanCheck("FILE_FINGERPRINT", fileHash != null, "Huella SHA-256 de los mismos bytes que se analizaron; un nombre no demuestra identidad."),
            PlanCheck("TECHNICAL_REPORT_VALID", evidence.completeness?.let { it.technicalValid && it.fileSha256 == fileHash && fileHash != null } == true,
                "Validación técnica exhaustiva vinculada a los mismos bytes; no certifica el alcance completo."),
            PlanCheck("XLSX_VALID", evidence.xlsxParsed == true, "El lector debe haber terminado correctamente."),
            PlanCheck("EXPECTED_COLUMNS", evidence.expectedColumnsRecognized == true, "Cuatro columnas verificadas en el alcance analizado."),
            PlanCheck("PRODUCT_HEADERS", evidence.headersInterpreted == true, "Encabezados de producto interpretados sin claves inventadas."),
            PlanCheck("SUMMARY_ROWS_SEPARATED", evidence.summariesSeparated == true, "Los resúmenes no se suman a series individuales."),
            PlanCheck("NO_CRITICAL_READ_ERRORS", evidence.criticalReadErrors == 0 && !result.sourceHasErrors, "Errores de lectura o comparación del origen bloquean los cambios."),
            PlanCheck("NO_UNINTERPRETED_ROWS", evidence.uninterpretedRelevantRows == 0, "Un recuento desconocido no equivale a cero filas sin interpretar."),
            PlanCheck("COMPLETE_BASELINE", snapshot?.complete == true, "Se requiere la lectura completa ya disponible del paso 2."),
            PlanCheck("SNAPSHOT_IDENTITIES_VALID", identitiesValid, "IDs, SKU, series, referencias, cantidades y estados deben ser inequívocos."),
            PlanCheck("NO_PENDING_CONFLICTS", result.conflicts.isEmpty(), "Ninguna coincidencia ambigua se resuelve automáticamente."),
            PlanCheck("FULL_REPORT_PROVEN", evidence.completeness?.let { it.state == ReportCompleteness.COMPLETE_VERIFIED && it.fileSha256 == fileHash && it.technicalValid && it.manifestDigest != null } == true, "El alcance completo no se presume: las ausencias se conservan y nunca se eliminan automáticamente."),
            PlanCheck("PRODUCTION_RULES_APPROVED", false, "La copia auditada presenta contradicciones y prohíbe borrar existencias. Requiere propuesta y pruebas antes de 4B."),
            PlanCheck("LEGACY_COUNTER_POLICY", false, "cantidadRestante conserva su valor; su semántica no está aprobada para sincronizar.")
        ).sortedBy { it.code }
        val informational = setOf("FULL_REPORT_PROVEN", "PRODUCTION_RULES_APPROVED", "LEGACY_COUNTER_POLICY")
        val globalBlockers = checks.filter { !it.satisfied && it.code !in informational }.map { it.code }.distinct()
        val operations = mutableListOf<SyncOperation>()
        fun emit(type: SyncOperationType, collection: String? = null, id: String? = null, sku: String? = null,
            serial: String? = null, productId: String? = null, before: Map<String, Any?> = emptyMap(),
            expected: Map<String, Any?> = emptyMap(), reason: String, refs: List<String> = emptyList(),
            group: String? = productId?.let { "product:$it" } ?: sku?.let { "sku:$it" }, version: String? = null,
            extraBlockers: List<String> = emptyList(), review: Boolean = false) {
            val mutation = type in setOf(SyncOperationType.CREATE_PRODUCT, SyncOperationType.CREATE_EXISTENCE,
                SyncOperationType.UPDATE_EXISTENCE, SyncOperationType.DELETE_EXISTENCE, SyncOperationType.UPDATE_PRODUCT_STOCK)
            val validPath = collection in setOf("productos", "existencias") && id != null && validId(id)
            val blockers = ((if (mutation) globalBlockers else emptyList()) + extraBlockers +
                (if (mutation && id != null && !validPath) listOf("INVALID_TARGET_PATH") else emptyList()) +
                (if (mutation && type !in setOf(SyncOperationType.CREATE_PRODUCT, SyncOperationType.CREATE_EXISTENCE) && validPath && version == null) listOf("MISSING_DOCUMENT_VERSION") else emptyList())).distinct().sorted()
            val status = when {
                blockers.isNotEmpty() || type == SyncOperationType.BLOCK_CONFLICT -> SyncValidation.BLOCKED
                review -> SyncValidation.REVIEW_REQUIRED
                type == SyncOperationType.SKIP_UNCHANGED -> SyncValidation.SKIPPED
                else -> SyncValidation.CANDIDATE
            }
            val risk = when {
                type == SyncOperationType.DELETE_EXISTENCE -> SyncRisk.CRITICAL
                type == SyncOperationType.BLOCK_CONFLICT || review -> SyncRisk.HIGH
                type == SyncOperationType.SKIP_UNCHANGED -> SyncRisk.NONE
                else -> SyncRisk.HIGH
            }
            operations += SyncOperation("", type, collection, id, if (validPath) "$collection/$id" else null, sku, serial, productId,
                before.toSortedMap(), expected.toSortedMap(), reason, status, risk, blockers, refs.sorted(), group, version)
        }
        result.conflicts.forEach { c -> emit(SyncOperationType.BLOCK_CONFLICT,
            sku = c.key.takeIf { c.kind.startsWith("SKU ") }, serial = c.key.takeIf { c.kind.startsWith("Serie ") || c.kind == "Fila Excel no válida" },
            reason = "${c.kind}: ${c.key}", refs = c.references, extraBlockers = listOf("COMPARISON_CONFLICT")) }
        if (!identitiesValid) emit(SyncOperationType.BLOCK_CONFLICT, reason = "La instantánea no permite validar identidades y referencias.",
            extraBlockers = listOf("SNAPSHOT_IDENTITIES_VALID"))
        globalBlockers.filter { it !in setOf("NO_PENDING_CONFLICTS", "SNAPSHOT_IDENTITIES_VALID") }.forEach { code ->
            emit(SyncOperationType.BLOCK_CONFLICT, reason = checks.firstOrNull { it.code == code }?.explanation ?: "Validación pendiente: $code", extraBlockers = listOf(code))
        }
        result.newProducts.forEach { p ->
            val total = sum(p.serials.map { it.quantity })
            val identity = idPolicy.product(p.code.orEmpty())
            emit(SyncOperationType.CREATE_PRODUCT, "productos", id = identity.documentId, sku = p.code, productId = identity.documentId,
                expected = mapOf("codigo" to p.code, "nombre" to p.name, "stock" to total, "estado" to "ACTIVO"),
                reason = "Producto nuevo por SKU exacto; ID determinista propuesto, comprobado contra la instantánea completa.",
                refs = listOf("${p.sheet}:${p.row}") + identity.references, extraBlockers = identity.blockers + listOfNotNull(if (identity.existing) "COMPARISON_NEW_IDENTITY_ALREADY_EXISTS" else null,
                    if (total == null || total != p.reportedQuantity) "INVALID_EXCEL_STOCK" else null))
        }
        result.newExistences.forEach { e ->
            val parent = idPolicy.product(e.productCode)
            val identity = idPolicy.existence(e.productCode, e.excel.code)
            emit(SyncOperationType.CREATE_EXISTENCE, "existencias", id = identity.documentId, sku = e.productCode, serial = e.excel.code, productId = parent.documentId,
                expected = mapOf("productoId" to parent.documentId, "codigoProducto" to e.productCode, "codigo" to e.excel.code,
                    "cantidad" to e.excel.quantity, "estado" to if (e.excel.quantity == 0L) "SIN_STOCK" else "DISPONIBLE"),
                reason = "Serie nueva, aunque el stock total pueda ser idéntico. Identidad compuesta determinista propuesta.",
                refs = listOf("${e.excel.sheet}:${e.excel.row}") + identity.references + parent.references, extraBlockers = identity.blockers + listOfNotNull(if (identity.existing) "COMPARISON_NEW_IDENTITY_ALREADY_EXISTS" else null) +
                    if (!e.excel.valid || e.excel.quantity == null || e.excel.quantity < 0) listOf("INVALID_SERIAL_ROW") else emptyList())
        }
        result.differentQuantities.forEach { m ->
            val e = m.firestore
            val live = entries[e.id]?.singleOrNull()
            val associated = live == e && products[e.productId]?.singleOrNull()?.code == m.productCode && e.productCode == m.productCode
            emit(SyncOperationType.UPDATE_EXISTENCE, "existencias", e.id, m.productCode, e.code, e.productId,
                mapOf("cantidad" to e.quantity, "estado" to e.state, "productoId" to e.productId, "codigoProducto" to e.productCode, "codigo" to e.code),
                mapOf("cantidad" to m.excel.quantity, "estado" to if (m.excel.quantity == 0L) "SIN_STOCK" else "DISPONIBLE"),
                "Cantidad individual diferente; no se modifica el historial.", version = e.version,
                refs = listOf("${m.excel.sheet}:${m.excel.row}"), extraBlockers = listOfNotNull(
                    if (!associated) "BASELINE_MISMATCH" else null,
                    if (!m.excel.valid || m.excel.code != e.code || m.excel.quantity == null || m.excel.quantity < 0) "INVALID_SERIAL_ROW" else null))
        }
        result.absentExistences.forEach { e -> emit(SyncOperationType.REVIEW_MISSING_EXISTENCE, "existencias", e.id, e.productCode, e.code, e.productId,
            mapOf("cantidad" to e.quantity, "estado" to e.state, "productoId" to e.productId, "codigo" to e.code), emptyMap(),
            "Ausente del Excel: conservar sin cambios. No se elimina ni se sustituye por cantidad cero.", version = e.version, review = true) }
        result.unchangedExistences.forEach { m -> emit(SyncOperationType.SKIP_UNCHANGED, "existencias", m.firestore.id,
            m.productCode, m.firestore.code, m.firestore.productId, mapOf("cantidad" to m.firestore.quantity), mapOf("cantidad" to m.excel.quantity),
            "Misma serie, producto y cantidad; cero escrituras.", version = m.firestore.version) }
        val stockIds = result.stockDifferences.map { it.productId }.toSet()
        result.existingProducts.filter { it.firestore.id !in stockIds }.forEach { m ->
            emit(SyncOperationType.SKIP_UNCHANGED, "productos", m.firestore.id, m.firestore.code, productId = m.firestore.id,
                before = mapOf("stock" to m.firestore.stock), expected = mapOf("stock" to m.firestore.stock),
                reason = "Stock igual al Excel; no es una orden de editar metadatos.", version = m.firestore.version)
        }
        result.stockDifferences.forEach { s ->
            val p = products[s.productId]?.singleOrNull()
            if (s.expectedExcel == null || s.expectedExcel < 0 || p == null || p.code != s.code || p.stock != s.stock) {
                emit(SyncOperationType.BLOCK_CONFLICT, "productos", s.productId, s.code, productId = s.productId,
                    before = mapOf("stock" to s.stock, "cantidadRestante" to s.remaining), reason = "Stock esperado desconocido o producto sin correspondencia inequívoca.",
                    extraBlockers = listOf("UNRESOLVED_STOCK"))
            } else if (s.stock != s.expectedExcel) {
                emit(SyncOperationType.UPDATE_PRODUCT_STOCK, "productos", s.productId, s.code, productId = s.productId,
                    before = mapOf("stock" to s.stock), expected = mapOf("stock" to s.expectedExcel), version = p.version,
                    reason = "Stock esperado del Excel; debe confirmarse con todas sus series en la misma unidad atómica.")
            }
            // cantidadRestante is informational and preserved, not a reason to block stock from valid Excel rows.
        }
        result.absentProducts.forEach { p -> emit(SyncOperationType.REVIEW_MISSING_PRODUCT, "productos", p.id, p.code, productId = p.id,
            before = mapOf("stock" to p.stock, "cantidadRestante" to p.remaining), reason = "Producto ausente del Excel: conservar documento e historial y revisar.", review = true, version = p.version) }
        val duplicatePaths = operations.filter { it.changesInventory && it.path != null }.groupBy { it.path }.filterValues { it.size > 1 }.keys
        val blockedGroups = operations.filter { ((it.validation == SyncValidation.BLOCKED || it.validation == SyncValidation.REVIEW_REQUIRED) &&
            (it.changesInventory || it.type == SyncOperationType.BLOCK_CONFLICT)) ||
            (it.changesInventory && it.path in duplicatePaths) }.mapNotNull { it.atomicGroup }.toSet()
        val finalOperations = operations.map { op ->
            val blockers = op.blockers + if (op.changesInventory && op.path in duplicatePaths) listOf("MULTIPLE_OPERATIONS_SAME_PATH") else emptyList()
            val groupBlockers = if (op.changesInventory && op.atomicGroup in blockedGroups) listOf("ATOMIC_GROUP_BLOCKED") else emptyList()
            if (blockers.isNotEmpty() || groupBlockers.isNotEmpty()) op.copy(validation = SyncValidation.BLOCKED,
                blockers = (blockers + groupBlockers).distinct().sorted()) else op
        }
            .map { it.copy(operationId = SyncCanonical.hash(operationValue(it))) }.distinctBy { it.operationId }.sortedBy { it.operationId }
        val fingerprint = snapshot?.let { SyncPlanIdentity.inventoryFingerprint(it) }
        val planId = SyncCanonical.hash(listOf("CLISA_EXCEL_REFERENCE_V1", fileHash, fingerprint, evidence.completeness?.manifestDigest, null,
            finalOperations.map { it.operationId }, checks.map { listOf(it.code, it.satisfied) }))
        val documents = snapshot?.let { it.products.size.toLong() + it.existences.size } ?: 0L
        return SyncPlan(4, "dryrun-$planId", evidence.fileName, fileHash, fingerprint, finalOperations, checks,
            SyncPlanEstimator.estimate(finalOperations, documents), snapshot?.finishedAtMillis, null)
    }

    private fun operationValue(o: SyncOperation): Any = listOf(o.type.name, o.collection, o.documentId, o.path, o.sku, o.serial,
        o.productId, o.before, o.expected, o.reason, o.validation.name, o.risk.name, o.blockers.sorted(), o.references.sorted(), o.atomicGroup, o.observedVersion)

    private fun sum(values: List<Long?>): Long? {
        var total = 0L
        for (n in values) { if (n == null || n < 0 || Long.MAX_VALUE - total < n) return null; total += n }
        return total
    }
}
