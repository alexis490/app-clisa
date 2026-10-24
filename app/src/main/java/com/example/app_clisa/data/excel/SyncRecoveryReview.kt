package com.example.app_clisa.data.excel

enum class SyncJournalState { APPLIED, FAILED }
data class SyncJournalEntry(val planId: String, val fileSha256: String, val operationId: String,
    val state: SyncJournalState, val afterFingerprint: String?)
data class SyncRecoveryDecision(val committedOperationIds: List<String>, val pendingOperationIds: List<String>,
    val failedOperationIds: List<String>, val blockers: List<String>) {
    val productionExecutable: Boolean get() = false
}

/** Reviews supplied journal data in memory; does not load/save a journal or resume any writes. */
object SyncRecoveryReview {
    fun inspect(plan: SyncPlan, journal: List<SyncJournalEntry>): SyncRecoveryDecision {
        val expected = plan.operations.filter { it.changesInventory }.map { it.operationId }.toSet()
        val blockers = mutableListOf<String>()
        if (journal.any { it.planId != plan.planId || plan.fileSha256 == null || it.fileSha256 != plan.fileSha256 }) blockers += "JOURNAL_IDENTITY_MISMATCH"
        if (journal.any { it.operationId !in expected }) blockers += "UNKNOWN_JOURNAL_OPERATION"
        if (journal.groupBy { it.operationId }.any { it.value.size != 1 }) blockers += "DUPLICATE_JOURNAL_OPERATION"
        if (journal.any { it.state == SyncJournalState.APPLIED && it.afterFingerprint.isNullOrBlank() }) blockers += "MISSING_AFTER_IMAGE_PROOF"
        val unique = journal.groupBy { it.operationId }.filterValues { it.size == 1 }.values.map { it.single() }
            .filter { it.planId == plan.planId && it.fileSha256 == plan.fileSha256 && it.operationId in expected }
        val committed = unique.filter { it.state == SyncJournalState.APPLIED && !it.afterFingerprint.isNullOrBlank() }.map { it.operationId }.sorted()
        val failed = unique.filter { it.state == SyncJournalState.FAILED }.map { it.operationId }.sorted()
        if (committed.isNotEmpty()) blockers += "PARTIAL_OR_COMPLETED_RUN_REQUIRES_RECONCILIATION"
        if (failed.isNotEmpty()) blockers += "FAILED_OPERATIONS_REQUIRE_REVALIDATION"
        return SyncRecoveryDecision(committed, (expected - committed.toSet()).sorted(), failed, blockers.distinct().sorted())
    }

    /** Stable for identical source bytes across changed baselines; still needs a trusted scope and approved policy. */
    fun sourceReplayKey(fileSha256: String, scope: String, policyVersion: String): String {
        require(fileSha256.matches(Regex("[0-9a-f]{64}")) && scope.isNotBlank() && policyVersion.isNotBlank())
        return SyncCanonical.hash(listOf("CLISA_SOURCE_REPLAY_KEY_V1", fileSha256, scope, policyVersion))
    }
}
