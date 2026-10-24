package com.example.app_clisa.data.excel

import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Canonical length-prefixed encoding: SKU delimiters, whitespace or Unicode cannot change field boundaries. */
internal object SyncCanonical {
    fun value(v: Any?): String = when (v) {
        null -> "n;"
        is String -> "s${v.length}:$v"
        is Long -> "i$v;"
        is Int -> "i$v;"
        is Boolean -> if (v) "b1;" else "b0;"
        is Map<*, *> -> v.entries.sortedBy { it.key as String }.joinToString("", "m${v.size}:") { value(it.key) + value(it.value) }
        is List<*> -> v.joinToString("", "l${v.size}:") { value(it) }
        else -> error("Unsupported canonical value")
    }
    fun hash(v: Any?): String = hex(MessageDigest.getInstance("SHA-256").digest(value(v).toByteArray(Charsets.UTF_8)))
    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val alphabet = "0123456789abcdef"
        bytes.forEach { byte -> val v = byte.toInt() and 255; append(alphabet[v ushr 4]); append(alphabet[v and 15]) }
    }
}

object SyncPlanIdentity {
    /** Caller must use the same file bytes parsed by the reader. This function never accesses Firestore. */
    fun fileSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
        return SyncCanonical.hex(digest.digest())
    }

    fun inventoryFingerprint(snapshot: InventoryReadSnapshot): String = SyncCanonical.hash(mapOf(
        "complete" to snapshot.complete,
        "products" to snapshot.products.map { p -> SyncCanonical.value(listOf(p.id, p.code, p.name, p.stock, p.remaining, p.version)) }.sorted(),
        "existences" to snapshot.existences.map { e -> SyncCanonical.value(listOf(e.id, e.productId, e.productCode, e.code, e.quantity, e.state, e.version)) }.sorted()
    ))
}

data class PlanFreshness(val matchesObservedBaseline: Boolean, val blockers: List<String>) {
    val productionExecutable: Boolean get() = false
}
data class PlanAgeReview(val withinConfiguredWindow: Boolean, val blockers: List<String>) {
    val productionExecutable: Boolean get() = false
}

/** Offline preflight only. A fresh SDK snapshot must be obtained/rechecked atomically by a future approved executor. */
object SyncPlanFreshness {
    /** A caller-supplied trusted clock/window is required; age is not proof of complete export or atomicity. */
    fun reviewAge(plan: SyncPlan, nowMillis: Long, maxAgeMillis: Long): PlanAgeReview {
        require(maxAgeMillis > 0 && nowMillis >= 0)
        val observed = plan.observedAtMillis
        val reason = when {
            observed == null -> "UNKNOWN_PLAN_TIME"
            observed < 0 || nowMillis < observed -> "CLOCK_INCONSISTENT"
            nowMillis - observed > maxAgeMillis -> "PLAN_EXPIRED"
            else -> null
        }
        return PlanAgeReview(reason == null, listOfNotNull(reason))
    }

    fun validate(plan: SyncPlan, fresh: InventoryReadSnapshot, fileSha256: String): PlanFreshness {
        val blockers = mutableListOf<String>()
        if (plan.fileSha256 == null || plan.fileSha256 != fileSha256.lowercase(Locale.ROOT)) blockers += "FILE_CHANGED_OR_UNVERIFIED"
        if (!fresh.complete) blockers += "INCOMPLETE_REVALIDATION"
        if (plan.baselineFingerprint == null || plan.baselineFingerprint != SyncPlanIdentity.inventoryFingerprint(fresh)) blockers += "INVENTORY_CHANGED"
        if (plan.operations.any { it.changesInventory && it.type !in setOf(SyncOperationType.CREATE_PRODUCT, SyncOperationType.CREATE_EXISTENCE) && it.path != null && it.observedVersion == null }) blockers += "MISSING_DOCUMENT_VERSION"
        return PlanFreshness(blockers.isEmpty(), blockers.sorted())
    }
}
