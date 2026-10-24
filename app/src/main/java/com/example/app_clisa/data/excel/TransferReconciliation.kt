package com.example.app_clisa.data.excel

enum class ReconciliationStatus { CONCILIADO, REQUIERE_REVISION, BLOQUEADO, SIN_CAMBIOS }
enum class ExportInclusion { INCLUIDO, NO_INCLUIDO, DESCONOCIDO }
enum class ReconciliationEvidenceKind { DECLARACION_ADMINISTRATIVA, REFERENCIA_VERIFICADA, INFERENCIA_SISTEMA }
data class TransferMovement(val id: String, val productId: String?, val productCode: String?, val existenceId: String?,
    val serial: String?, val quantity: Long?, val type: String?, val state: String?, val payment: String?,
    val formalization: String?, val recordedAtMillis: Long?, val formalizedAtMillis: Long?, val formalizedBy: String?,
    val origin: String?, val destination: String?, val version: String?) {
    fun identity(): List<Any?> = listOf(id, productId, productCode, existenceId, serial, quantity, type, state, payment,
        formalization, recordedAtMillis, formalizedAtMillis, formalizedBy, origin, destination, version)
}
data class ProductMovementSnapshot(val productId: String, val movements: List<TransferMovement>,
    val rangeFinished: Boolean, val readAtMillis: Long)
data class TransferReconciliationInput(val fileSha256: String, val plan: SyncPlan, val product: ComparedProduct,
    val source: List<ExcelProduct>, val entries: List<ComparedExistence>, val movements: ProductMovementSnapshot?,
    val declaredExportMillis: Long?, val uncertainExport: Boolean, val unresolvedSourceIssues: Boolean) {
    val fingerprint: String get() = SyncCanonical.hash(listOf("CLISA_TRANSFER_RECONCILIATION_V1", fileSha256,
        plan.planId, plan.baselineFingerprint, product.id, product.code, product.stock, product.remaining, product.version,
        source.map { listOf(it.code, it.name, it.reportedQuantity, it.sheet, it.row,
            it.serials.map { s -> listOf(s.code, s.quantity, s.sheet, s.row, s.valid) }) },
        entries.map { listOf(it.id, it.productId, it.productCode, it.code, it.quantity, it.state, it.version) }.sortedBy { SyncCanonical.value(it) },
        movements?.movements?.map { it.identity() }?.sortedBy { SyncCanonical.value(it) }, movements?.rangeFinished,
        declaredExportMillis, uncertainExport, unresolvedSourceIssues))
}
data class MovementReconciliationEvidence(val movementId: String, val inclusion: ExportInclusion,
    val kind: ReconciliationEvidenceKind, val reference: String, val reason: String, val reviewer: String, val reviewedAtMillis: Long)
data class TransferReconciliationProof(val contextFingerprint: String, val evidence: List<MovementReconciliationEvidence>,
    val exportReference: String, val completeScope: Boolean, val counterRepairReference: String? = null)
/** A trusted external adapter must verify authenticity and source scope. Android declarations never implement it. */
fun interface ReconciliationProofVerifier { fun verify(input: TransferReconciliationInput, proof: TransferReconciliationProof): Boolean }
data class TransferReconciliationResult(val productId: String, val status: ReconciliationStatus,
    val stockOficial: Long?, val stockApp: Long?, val cantidadRestante: Long?, val stockConciliado: Long?,
    val difference: Long?, val serialTargets: Map<String, Long>, val movements: List<TransferMovement>,
    val demonstratedIncluded: List<String>, val inclusionUnproven: List<String>, val evidence: List<MovementReconciliationEvidence>,
    val blockers: List<String>, val planOperationStates: Map<String, ReconciliationStatus>) {
    val productionExecutable: Boolean get() = false
}

object TransferReconciler {
    fun evaluate(input: TransferReconciliationInput, proof: TransferReconciliationProof? = null,
        verifier: ReconciliationProofVerifier = ReconciliationProofVerifier { _, _ -> false }): TransferReconciliationResult {
        val faults = mutableListOf<String>()
        val source = input.source.singleOrNull()
        val official = source?.reportedQuantity
        val rows = input.movements?.movements.orEmpty()
        val entries = input.entries
        input.plan.operations.filter { it.productId == input.product.id }.forEach { op ->
            if (op.type == SyncOperationType.UPDATE_PRODUCT_STOCK &&
                (op.before["stock"] != input.product.stock || op.observedVersion != input.product.version)) faults += "PLAN_PRODUCT_BASELINE_MISMATCH"
            if (op.type == SyncOperationType.UPDATE_EXISTENCE) {
                val entry = entries.singleOrNull { it.id == op.documentId }
                if (entry == null || op.before["cantidad"] != entry.quantity || op.observedVersion != entry.version) faults += "PLAN_EXISTENCE_BASELINE_MISMATCH"
            }
        }
        if (input.plan.fileSha256 != input.fileSha256 || !Regex("[a-f0-9]{64}").matches(input.fileSha256)) faults += "SOURCE_HASH_MISMATCH"
        if (source == null || source.code != input.product.code || input.product.code.isNullOrBlank() || input.product.code != input.product.code.trim()) faults += "PRODUCT_IDENTITY_AMBIGUOUS"
        if (input.product.stock == null || input.product.stock < 0 || input.product.version == null) faults += "APP_STOCK_OR_VERSION_UNKNOWN"
        if (input.unresolvedSourceIssues) faults += "SOURCE_ISSUES_REQUIRE_REVIEW"
        if (input.declaredExportMillis == null || input.uncertainExport) faults += "EXPORT_TIME_UNCERTAIN"
        if (input.declaredExportMillis != null && (input.declaredExportMillis < 0 || input.movements?.let { input.declaredExportMillis > it.readAtMillis } == true)) faults += "EXPORT_TIME_INVALID"
        if (input.movements == null || !input.movements.rangeFinished || input.movements.productId != input.product.id) faults += "MOVEMENT_SCOPE_INCOMPLETE"
        if (input.movements != null && (input.plan.observedAtMillis == null || input.plan.observedAtMillis > input.movements.readAtMillis ||
                input.movements.readAtMillis - input.plan.observedAtMillis > 15 * 60 * 1000)) faults += "PLAN_STALE"
        if (rows.map { it.id }.toSet().size != rows.size || rows.any { it.id.isBlank() }) faults += "DUPLICATE_MOVEMENT_ID"
        if (entries.any { it.productId != input.product.id || it.productCode != input.product.code || it.version == null || it.quantity == null || it.quantity < 0 ||
                it.state != if (it.quantity == 0L) "SIN_STOCK" else "DISPONIBLE" } ||
            entries.map { it.code }.toSet().size != entries.size) faults += "APP_SERIAL_IDENTITY_OR_VERSION_INVALID"
        val serials = source?.serials.orEmpty()
        if (official == null || official < 0 || serials.any { !it.valid || it.quantity == null || it.quantity < 0 || it.code.isBlank() || it.code != it.code.trim() } ||
            serials.map { it.code }.toSet().size != serials.size) faults += "EXCEL_SERIAL_OR_QUANTITY_INVALID"
        fun sum(values: List<Long?>): Long? = try { values.fold(0L) { a, b -> Math.addExact(a, requireNotNull(b)) } } catch (_: Exception) { null }
        if (sum(serials.map { it.quantity }) != official) faults += "EXCEL_SUMMARY_DETAIL_MISMATCH"
        val appCounterMismatch = sum(entries.map { it.quantity }) != input.product.stock
        // No deletion proposal is made when an existing physical identity disappears from the source.
        if (entries.any { e -> serials.none { it.code == e.code } }) faults += "ABSENT_SERIAL_DELETION_BLOCKED"
        rows.forEach { m ->
            if (m.productId != input.product.id || m.productCode != input.product.code || m.version == null || m.quantity == null || m.quantity <= 0 ||
                m.recordedAtMillis == null || m.recordedAtMillis < 0 || m.recordedAtMillis > (input.movements?.readAtMillis ?: 0) || m.state != "COMPLETADO") faults += "MOVEMENT_SCHEMA_INVALID:${m.id}"
            if (m.type != "SALIDA" || m.origin != "clisa" || m.destination != "toqen") faults += "OTHER_INVENTORY_MOVEMENT:${m.id}"
            if (m.serial.isNullOrBlank() || m.existenceId.isNullOrBlank() || entries.none { it.id == m.existenceId && it.code == m.serial }) faults += "MOVEMENT_SERIAL_UNKNOWN:${m.id}"
            if (m.formalization !in setOf("PENDIENTE", "COMPLETADO")) faults += "LEGACY_FORMALIZATION_UNVERIFIED:${m.id}"
            if (m.formalization == "COMPLETADO" && (m.formalizedAtMillis == null || m.formalizedBy.isNullOrBlank())) faults += "FORMALIZATION_FIELDS_INCOMPLETE:${m.id}"
        }
        val evidence = proof?.evidence.orEmpty()
        val trusted = proof != null && proof.contextFingerprint == input.fingerprint && proof.completeScope && proof.exportReference.isNotBlank() &&
            evidence.map { it.movementId }.toSet() == rows.map { it.id }.toSet() && evidence.size == rows.size &&
            evidence.all { it.kind == ReconciliationEvidenceKind.REFERENCIA_VERIFICADA && it.inclusion != ExportInclusion.DESCONOCIDO &&
                it.reference.isNotBlank() && it.reason.isNotBlank() && it.reviewer.isNotBlank() && it.reviewedAtMillis > 0 } && verifier.verify(input, proof)
        if (!trusted) faults += "TRUSTED_EXPORT_AND_MOVEMENT_EVIDENCE_MISSING"
        if (appCounterMismatch && !(trusted && proof?.counterRepairReference?.isNotBlank() == true)) faults += "APP_STOCK_DETAIL_MISMATCH"
        val included = if (trusted) evidence.filter { it.inclusion == ExportInclusion.INCLUIDO }.map { it.movementId } else emptyList()
        var target: Long? = null
        val targets = linkedMapOf<String, Long>()
        if (faults.isEmpty()) {
            try {
                serials.forEach { targets[it.code] = requireNotNull(it.quantity) }
                rows.filter { it.id !in included }.forEach { m ->
                    val previous = requireNotNull(targets[m.serial]) { "Serie no encontrada en Excel" }
                    targets[m.serial!!] = Math.subtractExact(previous, requireNotNull(m.quantity))
                }
                if (targets.values.any { it < 0 }) faults += "NEGATIVE_RECONCILED_QUANTITY"
                else target = targets.values.fold(0L, Math::addExact)
            } catch (_: Exception) { faults += "RECONCILIATION_ARITHMETIC_OR_SERIAL_INVALID" }
        }
        if (faults.isNotEmpty()) { target = null; targets.clear() }
        val status = if (faults.isNotEmpty()) {
            if (faults.all { it == "TRUSTED_EXPORT_AND_MOVEMENT_EVIDENCE_MISSING" }) ReconciliationStatus.REQUIERE_REVISION else ReconciliationStatus.BLOQUEADO
        } else if (target == input.product.stock && entries.all { targets[it.code] == it.quantity } && targets.size == entries.size) ReconciliationStatus.SIN_CAMBIOS
        else ReconciliationStatus.CONCILIADO
        val operationStates = input.plan.operations.filter { it.productId == input.product.id }.associate { op ->
            val agrees = when (op.type) {
                SyncOperationType.SKIP_UNCHANGED -> status == ReconciliationStatus.SIN_CAMBIOS
                SyncOperationType.UPDATE_PRODUCT_STOCK -> target != null && op.expected["stock"] == target
                SyncOperationType.CREATE_EXISTENCE, SyncOperationType.UPDATE_EXISTENCE -> target != null && op.expected["cantidad"] == targets[op.serial]
                else -> false
            }
            op.operationId to when {
                op.type in setOf(SyncOperationType.DELETE_EXISTENCE, SyncOperationType.BLOCK_CONFLICT, SyncOperationType.REVIEW_MISSING_PRODUCT) -> ReconciliationStatus.BLOQUEADO
                status == ReconciliationStatus.REQUIERE_REVISION -> ReconciliationStatus.REQUIERE_REVISION
                op.blockers.isNotEmpty() || !agrees -> ReconciliationStatus.BLOQUEADO
                else -> status
            }
        }
        return TransferReconciliationResult(input.product.id, status, official, input.product.stock, input.product.remaining, target,
            if (official != null && input.product.stock != null) try { Math.subtractExact(official, input.product.stock) } catch (_: Exception) { null } else null,
            targets, rows, included, rows.filter { !trusted }.map { it.id }, evidence, faults.distinct(), operationStates)
    }
}
