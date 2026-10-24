package com.example.app_clisa.data.excel

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ClassificationChoice(val key: String, val field: String, val id: String, val reason: String, val bulk: Boolean)
class ClassificationReviewStore(private val directory: File, private val uid: String) {
    private val store = IssueReviewStore(directory, uid)
    fun load(hash: String) = store.load(hash)
    fun approve(review: ClassificationReview, choices: List<ClassificationChoice>, role: String, expectedRevision: Int, at: Long): IssueReviewJournal {
        require(role in setOf("ADMIN", "SUPER_ADMIN"))
        val previous = load(review.fileSha256); require(previous.revision == expectedRevision)
        require(choices.map { it.key to it.field }.toSet().size == choices.size)
        val decisions = choices.map { review.decision(it.key, it.field, it.id, it.reason, it.bulk, uid, at) }.filter { d ->
            previous.latest[d.issueId]?.let { it.proposed == d.proposed && it.reason == d.reason && it.evidence == d.evidence } != true }
        val next = if (decisions.isEmpty()) previous else store.saveBatch(review.journalCatalog, decisions, uid, role, expectedRevision)
        saveManifest(review, next); return next
    }
    fun saveManifest(review: ClassificationReview, journal: IssueReviewJournal) {
        val json = SyncExecutionContract.jsonValue(review.manifest(journal))
        val body = "${journal.revision}\n$json"
        val checksum = SyncPlanIdentity.fileSha256(body.byteInputStream())
        require(body.toByteArray().size + 65 <= 8 * 1024 * 1024) { "Expediente de clasificación supera 8 MB; conserva las decisiones locales." }
        require(directory.isDirectory || directory.mkdirs())
        val target = manifestFile(review.fileSha256); val temp = File.createTempFile("classification-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { out -> out.write("$checksum\n$body".toByteArray()); out.fd.sync() }
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
        finally { temp.delete() }
    }
    fun readManifest(hash: String): String? {
        val file = manifestFile(hash); if (!file.exists()) return null
        require(file.length() <= 8 * 1024 * 1024)
        val text = file.readText(); val checksum = text.substringBefore('\n'); val body = text.substringAfter('\n')
        require(SyncPlanIdentity.fileSha256(body.byteInputStream()) == checksum) { "Expediente de clasificación dañado." }
        require(body.substringBefore('\n').toIntOrNull() == load(hash).revision) { "Las aprobaciones cambiaron. Abre la clasificación y revalida el expediente antes de exportar." }
        val json = body.substringAfter('\n')
        return json
    }
    private fun manifestFile(hash: String): File {
        require(Regex("[a-f0-9]{64}").matches(hash))
        return File(directory, "${SyncCanonical.hash(uid)}-$hash.classification-v1")
    }
}
