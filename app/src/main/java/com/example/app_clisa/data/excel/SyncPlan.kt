package com.example.app_clisa.data.excel

enum class SyncOperationType {
    CREATE_PRODUCT, CREATE_EXISTENCE, UPDATE_EXISTENCE, DELETE_EXISTENCE,
    UPDATE_PRODUCT_STOCK, SKIP_UNCHANGED, BLOCK_CONFLICT, REVIEW_MISSING_PRODUCT, REVIEW_MISSING_EXISTENCE
}
enum class SyncValidation { CANDIDATE, REVIEW_REQUIRED, BLOCKED, SKIPPED }
enum class SyncRisk { NONE, LOW, HIGH, CRITICAL }

/** Values are document fields, not an SDK payload. Only quantities/stock/state are proposed for updates. */
data class SyncOperation(
    val operationId: String, val type: SyncOperationType, val collection: String?, val documentId: String?,
    val path: String?, val sku: String?, val serial: String?, val productId: String?,
    val before: Map<String, Any?>, val expected: Map<String, Any?>,
    val reason: String, val validation: SyncValidation, val risk: SyncRisk,
    val blockers: List<String>, val references: List<String>, val atomicGroup: String?, val observedVersion: String?
) {
    val changesInventory: Boolean get() = type in setOf(SyncOperationType.CREATE_PRODUCT, SyncOperationType.CREATE_EXISTENCE,
        SyncOperationType.UPDATE_EXISTENCE, SyncOperationType.DELETE_EXISTENCE, SyncOperationType.UPDATE_PRODUCT_STOCK)
}

/** Local parsing evidence is not an authorization or a certificate of a complete export. */
data class ReportReadEvidence(
    val fileName: String? = null, val fileSha256: String? = null,
    val xlsxParsed: Boolean? = null, val expectedColumnsRecognized: Boolean? = null,
    val headersInterpreted: Boolean? = null, val summariesSeparated: Boolean? = null,
    val criticalReadErrors: Int? = null, val uninterpretedRelevantRows: Int? = null,
    val declaredScope: String? = null, val completeness: CompletenessAssessment? = null, val temporal: ExportTemporalReview? = null
) {
    companion object {
        fun fromReadResult(report: ExcelInventory, fileName: String, fileSha256: String, temporal: ExportTemporalReview? = null): ReportReadEvidence {
            val structure = report.structure
            return ReportReadEvidence(fileName, fileSha256, structure?.xlsxValidated,
                structure?.let { it.sheets > 0 && it.sheetsWithHeaders == it.sheets },
                report.products.isNotEmpty() && report.products.all { !it.code.isNullOrBlank() }, true, report.errorCount,
                structure?.let { it.uninterpretedRows + it.preambleRows }, null,
                ExcelCompletenessValidator.assess(report, fileSha256), temporal)
        }
    }
}
data class PlanCheck(val code: String, val satisfied: Boolean, val explanation: String)
data class SyncPlanCost(
    val operationCounts: Map<SyncOperationType, Long>, val potentialWritesByType: Map<SyncOperationType, Long>,
    val potentialInventoryWrites: Long, val potentialDeletes: Long, val potentialAuditWrites: Long,
    val potentialTotalWrites: Long, val comparisonDocumentsAlreadyRead: Long,
    val dryRunAdditionalReads: Long, val futureFullRevalidationReads: Long,
    val futureKnownTargetReadsLowerBound: Long, val exceedsFullDailyReadQuota: Boolean,
    val exceedsFullDailyWriteQuota: Boolean, val exceedsFullDailyDeleteQuota: Boolean,
    val remainingDailyQuotaKnown: Boolean = false
)
data class SyncPlan(
    val schemaVersion: Int, val planId: String, val sourceFileName: String?, val fileSha256: String?, val baselineFingerprint: String?,
    val operations: List<SyncOperation>, val checks: List<PlanCheck>, val cost: SyncPlanCost,
    val observedAtMillis: Long?, val temporal: ExportTemporalReview? = null
) {
    // No constructor/copy parameter can turn a step 4A artifact into an executable authorization.
    val destructiveEligible: Boolean get() = false
    val productionExecutable: Boolean get() = false
}

/** Estimates a hypothetical accepted plan; blocked proposals remain visible in this upper scenario. */
object SyncPlanEstimator {
    private val writes = setOf(SyncOperationType.CREATE_PRODUCT, SyncOperationType.CREATE_EXISTENCE,
        SyncOperationType.UPDATE_EXISTENCE, SyncOperationType.UPDATE_PRODUCT_STOCK)

    fun estimate(operations: List<SyncOperation>, baselineDocuments: Long): SyncPlanCost {
        require(baselineDocuments >= 0)
        val changes = operations.filter { it.changesInventory }
        val byType = SyncOperationType.entries.associateWith { t -> operations.count { it.type == t }.toLong() }
        val writeCounts = writes.sortedBy { it.name }.associateWith { byType.getValue(it) }
        val inventoryWrites = writeCounts.values.sum()
        val deletes = byType.getValue(SyncOperationType.DELETE_EXISTENCE)
        val groups = changes.mapNotNull { it.atomicGroup }.toSet().size.toLong()
        // Proposed future contract: start/final job + one immutable audit record per mutation + checkpoint per group.
        val audit = if (changes.isEmpty()) 0L else 2L + changes.size + groups
        val targets = changes.flatMap { op -> listOfNotNull(op.path, op.productId?.let { "productos/$it" }) }.toSet().size.toLong()
        val revalidation = if (changes.isEmpty()) 0L else baselineDocuments
        return SyncPlanCost(byType, writeCounts, inventoryWrites, deletes, audit, inventoryWrites + audit,
            baselineDocuments, 0, revalidation, targets, baselineDocuments + revalidation + targets > 50_000,
            inventoryWrites + audit > 20_000, deletes > 20_000)
    }
}
