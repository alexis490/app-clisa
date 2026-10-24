package com.example.app_clisa.data.excel

import java.io.File

/** Separate namespace; reuses the versioned journal, atomic persistence and role checks from 4B.2B.1. */
class ReconciliationReviewStore(directory: File, uid: String, val input: TransferReconciliationInput) {
    private val store = IssueReviewStore(directory, uid)
    private val productPrefix = "${input.product.id.length}:${input.product.id}:"
    private fun originalMetadata(movementId: String): String {
        val m = input.movements?.movements?.singleOrNull { it.id == movementId }
        return productPrefix + SyncExecutionContract.jsonValue(mapOf(
            "schemaVersion" to 1, "contextFingerprint" to input.fingerprint, "fileSha256" to input.fileSha256,
            "planId" to input.plan.planId, "baselineFingerprint" to input.plan.baselineFingerprint,
            "productId" to input.product.id, "productCode" to input.product.code,
            "stockApp" to input.product.stock, "cantidadRestante" to input.product.remaining,
            "stockOficial" to input.source.singleOrNull()?.reportedQuantity,
            "declaredExportMillis" to input.declaredExportMillis, "uncertainExport" to input.uncertainExport,
            "movementId" to movementId, "movementOriginal" to m?.identity(),
            "productVersion" to input.product.version,
            "existenceVersions" to input.entries.sortedBy { it.id }.map { listOf(it.id, it.version) },
            "movementVersions" to input.movements?.movements?.sortedBy { it.id }?.map { listOf(it.id, it.version) },
            "evidenceKind" to ReconciliationEvidenceKind.DECLARACION_ADMINISTRATIVA.name
        )).also { require(it.toByteArray(Charsets.UTF_8).size <= 55_000) { "Contexto demasiado grande para el expediente local; requiere revisión administrativa separada." } }
    }
    private val catalog = IssueReviewCatalog(input.fileSha256, ExcelInventory(emptyList(),
        (input.movements?.movements.orEmpty().map { it.id }.ifEmpty { listOf("PRODUCT_SCOPE") }).map { movementId ->
            ExcelIssue("CONCILIACION", null, originalMetadata(movementId), ExcelIssueKind.OTHER,
                productName = input.product.name, serialCode = movementId)
        }, 0, 0))
    fun load(): IssueReviewJournal = store.load(input.fileSha256)
    fun decisionFor(movementId: String, journal: IssueReviewJournal): IssueDecision? =
        catalog.items.singleOrNull { it.issue.serialCode == movementId }?.let { journal.latest[it.id] }
    fun current(journal: IssueReviewJournal): List<IssueDecision> = journal.latest.values.filter { d -> catalog.items.any { it.id == d.issueId } }
    fun staleCount(journal: IssueReviewJournal): Int = journal.latest.values.count { d ->
        // Other products have independent contexts and are not invalidated by this product's review.
        d.original.startsWith(productPrefix) && catalog.items.none { it.id == d.issueId }
    }
    fun save(movementId: String, inclusion: ExportInclusion, reason: String, reference: String, uid: String, role: String,
        expectedRevision: Int, atMillis: Long): IssueReviewJournal {
        require(reason.isNotBlank() && reference.isNotBlank()) { "Documenta justificación y referencia; son una declaración, no evidencia verificada." }
        val item = catalog.items.single { it.issue.serialCode == movementId }
        val decision = IssueDecision(item.id, ExcelIssueKind.OTHER, catalog.original(item), inclusion.name, reason, reference, uid,
            atMillis, if (inclusion == ExportInclusion.DESCONOCIDO) IssueReviewState.BLOQUEADO else IssueReviewState.EN_REVISION, input.fileSha256)
        return store.save(catalog, decision, uid, role, expectedRevision)
    }
}
