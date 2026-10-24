package com.example.app_clisa.data.excel

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ImportFinalSummary(val products: Int, val proposed: Int, val verified: Int, val blocked: Int,
    val unchanged: Int, val reviewPending: Int, val reasons: List<String>,
    val estimatedReads: Long, val estimatedWrites: Long) {
    val productionExecutable: Boolean get() = false
}
object ImportFinalization {
    fun summarize(plan: SyncPlan, excel: ExcelInventory): ImportFinalSummary {
        val changes = plan.operations.filter { it.changesInventory }
        val verified = changes.count { op -> op.blockers.isEmpty() && op.validation == SyncValidation.CANDIDATE }
        return ImportFinalSummary(excel.products.size, changes.size, verified, changes.size - verified,
            plan.operations.count { it.type == SyncOperationType.SKIP_UNCHANGED },
            plan.operations.count { it.type in setOf(SyncOperationType.REVIEW_MISSING_EXISTENCE, SyncOperationType.REVIEW_MISSING_PRODUCT) },
            (changes.flatMap { it.blockers } + listOf("SIGNED_ADMIN_APPROVAL_REQUIRED", "PRODUCTION_EXECUTOR_DISABLED")).distinct().sorted(),
            plan.cost.futureFullRevalidationReads + plan.cost.futureKnownTargetReadsLowerBound, plan.cost.potentialTotalWrites)
    }
    /** Explicit offline transfer only; no SDK, signing or mutation of the original workbook. */
    fun writePackage(source: File, destination: OutputStream, expectedHash: String, contract: String, review: String) {
        require(source.length() <= XlsxInventoryReader.MAX_FILE_BYTES)
        require(source.inputStream().use { SyncPlanIdentity.fileSha256(it) } == expectedHash) { "El Excel cambió; vuelve a leerlo y comparar." }
        require(contract.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "El contrato supera el límite administrativo de 8 MB; no se omitieron documentos." }
        require(review.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "El expediente de revisión supera el límite de exportación." }
        ZipOutputStream(destination).use { zip ->
            zip.putNextEntry(ZipEntry("source.xlsx")); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            listOf("contract.json" to contract, "review.json" to review).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
    }
}
