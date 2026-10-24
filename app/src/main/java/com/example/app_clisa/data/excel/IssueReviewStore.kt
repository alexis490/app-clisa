package com.example.app_clisa.data.excel

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** App-private, versioned journal. Checksum detects corruption, NOT an administrative signature. */
class IssueReviewStore(private val directory: File, private val ownerUid: String) {
    init { require(ownerUid.isNotBlank()) }
    private fun path(hash: String): File {
        require(Regex("[a-f0-9]{64}").matches(hash))
        return File(directory, "${SyncCanonical.hash(ownerUid)}-$hash.review-v1")
    }
    @Synchronized fun load(hash: String): IssueReviewJournal {
        val file = path(hash)
        if (!file.exists()) return IssueReviewJournal(hash)
        require(file.length() <= 32_000_000) { "Expediente demasiado grande." }
        val bytes = file.readBytes()
        require(bytes.size >= 32) { "Expediente incompleto: no se reiniciará automáticamente." }
        val payload = bytes.copyOfRange(32, bytes.size)
        require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload))) { "Expediente dañado: conservar para revisión." }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readUTF() == "CLISA_ISSUE_REVIEW" && input.readInt() == 1 && input.readUTF() == ownerUid)
            require(input.readUTF() == hash)
            val count = input.readInt(); require(count in 0..10_000)
            val events = List(count) { IssueDecision(input.readUTF(), ExcelIssueKind.valueOf(input.readUTF()), input.readUTF(),
                input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(),
                IssueReviewState.valueOf(input.readUTF()), input.readUTF()) }
            require(input.available() == 0 && events.all { it.sourceSha256 == hash && it.reviewer == ownerUid })
            IssueReviewJournal(hash, events)
        }
    }
    /** Verified principal comes from the auth adapter; no Firebase dependency in local persistence/tests. */
    @Synchronized fun save(catalog: IssueReviewCatalog, decision: IssueDecision, verifiedUid: String,
        verifiedRole: String, expectedRevision: Int): IssueReviewJournal = saveBatch(catalog, listOf(decision), verifiedUid, verifiedRole, expectedRevision)
    @Synchronized fun saveBatch(catalog: IssueReviewCatalog, decisions: List<IssueDecision>, verifiedUid: String,
        verifiedRole: String, expectedRevision: Int): IssueReviewJournal = synchronized(lock) {
        require(decisions.isNotEmpty() && verifiedUid == ownerUid && decisions.all { it.reviewer == ownerUid } && verifiedRole in listOf("ADMIN", "SUPER_ADMIN")) { "Solo ADMIN y SUPER_ADMIN activos pueden proponer resoluciones." }
        val previous = load(catalog.sourceSha256)
        require(previous.revision == expectedRevision) { "El expediente cambió; vuelve a abrir la incidencia." }
        require(previous.revision + decisions.size <= 10_000) { "Límite de eventos alcanzado. Conserva el expediente." }
        var next = previous
        decisions.forEach { decision -> catalog.validate(decision, next); next = next.copy(events = next.events + decision) }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeUTF("CLISA_ISSUE_REVIEW"); out.writeInt(1); out.writeUTF(ownerUid); out.writeUTF(next.sourceSha256)
            out.writeInt(next.events.size)
            next.events.forEach { d ->
                out.writeUTF(d.issueId); out.writeUTF(d.kind.name); out.writeUTF(d.original); out.writeUTF(d.proposed)
                out.writeUTF(d.reason); out.writeUTF(d.evidence); out.writeUTF(d.reviewer); out.writeLong(d.atMillis)
                out.writeUTF(d.state.name); out.writeUTF(d.sourceSha256)
            }
        }
        val payload = buffer.toByteArray(); require(payload.size <= 31_999_968)
        require(directory.isDirectory || directory.mkdirs())
        val target = path(next.sourceSha256)
        val temporary = File.createTempFile("issue-review-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { out -> out.write(MessageDigest.getInstance("SHA-256").digest(payload)); out.write(payload); out.fd.sync() }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
        next
    }
    companion object { private val lock = Any() }
}
