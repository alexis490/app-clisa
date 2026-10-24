package com.example.app_clisa.data.excel

import java.math.BigInteger
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

enum class ReportCompleteness { COMPLETE_VERIFIED, INCOMPLETE, UNKNOWN }
data class ExportManifest(val fileSha256: String, val scope: String, val exportedAtMillis: Long,
    val productGroups: Int, val serialRows: Int, val totalQuantity: Long, val unfiltered: Boolean,
    val authorityKeyId: String, val signatureBase64: String) {
    fun signingBytes(): ByteArray = SyncCanonical.value(listOf("CLISA_EXPORT_MANIFEST_V1", fileSha256, scope, exportedAtMillis,
        productGroups, serialRows, totalQuantity, unfiltered, authorityKeyId)).toByteArray(Charsets.UTF_8)
}
data class ExportAuthorityPolicy(val scope: String, val nowMillis: Long, val maxAgeMillis: Long,
    val trustedPublicKeys: Map<String, PublicKey>)
class CompletenessAssessment internal constructor(val state: ReportCompleteness, val technicalValid: Boolean,
    val reasons: List<String>, val fileSha256: String?, val manifestDigest: String?)

/** No authority is configured by default. Parsing validity never implies full export scope. */
object ExcelCompletenessValidator {
    fun assess(report: ExcelInventory, fileSha256: String?, manifest: ExportManifest? = null,
        policy: ExportAuthorityPolicy? = null): CompletenessAssessment {
        val faults = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val structure = report.structure
        if (report.errorCount > 0 || report.issues.isNotEmpty()) faults += "READER_ISSUES"
        if (structure == null) unknown += "STRUCTURE_TRACKING_MISSING"
        else {
            if (!structure.xlsxValidated) unknown += "XLSX_CONTAINER_NOT_VERIFIED"
            val counts = listOf(structure.sheets, structure.sheetsWithHeaders, structure.nonEmptyRows,
                structure.columnHeaderRows, structure.productHeaderRows, structure.serialRows,
                structure.standaloneSummaryRows, structure.uninterpretedRows, structure.preambleRows)
            if (counts.any { it < 0 }) faults += "INVALID_STRUCTURE_COUNTS"
            if (structure.sheets <= 0 || structure.sheetsWithHeaders != structure.sheets ||
                structure.columnHeaderRows < structure.sheetsWithHeaders) faults += "MISSING_REQUIRED_COLUMNS"
            if (structure.uninterpretedRows > 0) faults += "UNINTERPRETED_ROWS"
            if (structure.preambleRows > 0) unknown += "UNCLASSIFIED_PREAMBLE"
            if (structure.productHeaderRows != report.products.size || structure.serialRows != report.serialRowCount ||
                structure.nonEmptyRows.toLong() != structure.columnHeaderRows.toLong() + structure.productHeaderRows + structure.serialRows +
                structure.standaloneSummaryRows + structure.uninterpretedRows + structure.preambleRows ||
                report.summaryRows != structure.productHeaderRows + structure.standaloneSummaryRows) faults += "ROW_ACCOUNTING_MISMATCH"
        }
        if (report.products.isEmpty()) faults += "NO_PRODUCT_GROUPS"
        if (report.products.any { it.code.isNullOrBlank() || it.code != it.code.trim() }) faults += "INVALID_PRODUCT_CODE"
        if (report.products.groupBy { it.code }.values.any { it.size > 1 }) faults += "DUPLICATE_PRODUCT_CODE"
        val serials = report.products.flatMap { it.serials }
        if (serials.groupBy { it.code.trim() }.any { it.key.isEmpty() || it.value.size > 1 }) faults += "DUPLICATE_OR_EMPTY_SERIAL"
        if (serials.any { !it.valid || it.code != it.code.trim() || it.quantity == null || it.quantity < 0 }) faults += "INVALID_SERIAL_ROW"
        report.products.forEach { p ->
            val total = total(p.serials)
            if ((p.reportedSerialCount != null && p.reportedSerialCount != p.serials.size) ||
                (p.reportedQuantity != null && p.reportedQuantity != total) || total == null || (p.serials.isEmpty() && p.reportedQuantity != 0L)) faults += "SUMMARY_DETAIL_MISMATCH"
            if (p.serials.any { it.sheet != p.sheet || it.row <= p.row }) faults += "INVALID_GROUP_MEMBERSHIP"
        }
        fun result(state: ReportCompleteness, reasons: List<String>, digest: String? = null) =
            CompletenessAssessment(state, faults.isEmpty() && unknown.isEmpty(), reasons.distinct().sorted(), fileSha256, digest)
        if (faults.isNotEmpty()) return result(ReportCompleteness.INCOMPLETE, faults + unknown)
        if (unknown.isNotEmpty()) return result(ReportCompleteness.UNKNOWN, unknown)
        if (manifest == null || policy == null) return result(ReportCompleteness.UNKNOWN, listOf("TRUSTED_EXPORT_EVIDENCE_MISSING"))
        val key = policy.trustedPublicKeys[manifest.authorityKeyId]
            ?: return result(ReportCompleteness.UNKNOWN, listOf("EXPORT_AUTHORITY_NOT_CONFIGURED"))
        val signatureValid = runCatching {
            Signature.getInstance("SHA256withRSA").run { initVerify(key); update(manifest.signingBytes()); verify(Base64.getDecoder().decode(manifest.signatureBase64)) }
        }.getOrDefault(false)
        if (!signatureValid) faults += "INVALID_EXPORT_SIGNATURE"
        if (!manifest.unfiltered || manifest.scope != policy.scope) faults += "PARTIAL_OR_WRONG_EXPORT_SCOPE"
        if (fileSha256 == null || !fileSha256.matches(Regex("[0-9a-f]{64}")) || manifest.fileSha256 != fileSha256) faults += "EXPORT_HASH_MISMATCH"
        if (manifest.productGroups != report.products.size || manifest.serialRows != serials.size || manifest.totalQuantity != total(serials)) faults += "EXPORT_CONTROL_TOTAL_MISMATCH"
        if (policy.maxAgeMillis <= 0 || manifest.exportedAtMillis < 0 || manifest.exportedAtMillis > policy.nowMillis ||
            policy.nowMillis - manifest.exportedAtMillis > policy.maxAgeMillis) faults += "EXPORT_TIME_INVALID"
        return if (faults.isNotEmpty()) result(ReportCompleteness.INCOMPLETE, faults)
        else result(ReportCompleteness.COMPLETE_VERIFIED, listOf("SIGNED_UNFILTERED_EXPORT_VERIFIED"), SyncCanonical.hash(manifest.signingBytes().toList().map { it.toInt() }))
    }

    private fun total(rows: List<ExcelSerial>): Long? {
        var sum = BigInteger.ZERO
        for (row in rows) { val n = row.quantity ?: return null; if (n < 0) return null; sum += BigInteger.valueOf(n) }
        return runCatching { sum.longValueExact() }.getOrNull()
    }
}
