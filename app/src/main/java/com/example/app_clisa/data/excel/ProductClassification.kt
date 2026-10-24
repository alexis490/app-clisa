package com.example.app_clisa.data.excel

import java.text.Normalizer
import java.util.Locale

enum class ClassificationState { ASIGNADO, REVISAR, SIN_IDENTIFICAR }
data class ClassificationReference(val id: String, val name: String, val active: Boolean)
data class ClassificationCatalog(val categories: List<ClassificationReference>, val brands: List<ClassificationReference>) {
    val digest: String by lazy { SyncCanonical.hash(mapOf("categories" to categories.sortedBy { it.id }.map { listOf(it.id, it.name, it.active) },
        "brands" to brands.sortedBy { it.id }.map { listOf(it.id, it.name, it.active) })) }
}
data class ClassificationAlias(val type: String, val name: String, val aliases: List<String>, val exclusions: List<String>)
data class ClassificationRules(val version: String, val digest: String, val aliases: List<ClassificationAlias>) {
    companion object {
        fun read(text: String): ClassificationRules {
            val normalized = text.replace("\r\n", "\n")
            val lines = normalized.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            val header = lines.first().split('\t'); require(header.size == 2 && header[0] == "version")
            val aliases = lines.drop(1).map { line -> val parts = line.split('\t'); require(parts.size in 3..4 && parts[0] in setOf("category", "brand"))
                ClassificationAlias(parts[0], parts[1], parts[2].split('|').filter { it.isNotBlank() }, parts.getOrNull(3).orEmpty().split('|').filter { it.isNotBlank() }) }
            return ClassificationRules(header[1], SyncPlanIdentity.fileSha256(normalized.byteInputStream()), aliases)
        }
    }
}
data class ClassificationProduct(val key: String, val documentId: String?, val sku: String?, val name: String,
    val categoryId: String?, val brandId: String?, val description: String?, val identityValid: Boolean) {
    val metadataHash: String get() = SyncCanonical.hash(listOf(documentId, sku, name, categoryId, brandId, description, identityValid))
}
data class ClassificationCandidate(val id: String, val rules: List<String>)
data class ClassificationSuggestion(val field: String, val original: String?, val state: ClassificationState,
    val proposed: String?, val candidates: List<ClassificationCandidate>, val preserved: Boolean, val explanation: String)
data class ClassifiedProduct(val product: ClassificationProduct, val category: ClassificationSuggestion,
    val brand: ClassificationSuggestion, val descriptionPending: Boolean)

object ProductClassifier {
    fun normalize(value: String): String = Regex("[\\p{L}\\p{N}]+").findAll(Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)).joinToString(" ") { it.value }
    private fun matches(text: String, phrase: String): Boolean = normalize(phrase).let { it.isNotBlank() && " $text ".contains(" $it ") }
    fun products(excel: ExcelInventory, inventory: InventoryReadSnapshot): List<ClassificationProduct> {
        val policy = InventoryIdPolicy(inventory)
        val byCode = inventory.products.groupBy { it.code }
        return excel.products.groupBy { it.code?.takeIf { c -> c.isNotBlank() } ?: "missing:${it.sheet}:${it.row}" }.map { (key, groups) ->
            val source = groups.first(); val code = source.code
            val identity = code?.let { policy.product(it) }
            val existing = byCode[code].orEmpty().singleOrNull()
            val valid = groups.size == 1 && identity?.valid == true && existing?.classificationFieldsValid != false &&
                (existing?.name.isNullOrBlank() || normalize(existing!!.name!!) == normalize(source.name))
            ClassificationProduct(key, identity?.documentId, code, existing?.name?.takeIf { it.isNotBlank() } ?: source.name,
                existing?.categoryId, existing?.brandId, existing?.description, valid)
        }
    }
    fun classify(product: ClassificationProduct, catalog: ClassificationCatalog, rules: ClassificationRules): ClassifiedProduct {
        val name = normalize(product.name)
        fun field(type: String, original: String?, entries: List<ClassificationReference>): ClassificationSuggestion {
            val candidates = entries.filter { it.active }.mapNotNull { entry ->
                val configured = rules.aliases.filter { it.type == type && normalize(it.name) == normalize(entry.name) }
                val excluded = configured.any { a -> a.exclusions.any { matches(name, it) } }
                val applied = if (excluded) emptyList() else (listOf(entry.name).filter { matches(name, it) }.map { "catalog-name:${normalize(it)}" } +
                    configured.flatMap { it.aliases }.filter { matches(name, it) }.map { "explicit-alias:${normalize(it)}" }).distinct().sorted()
                if (applied.isEmpty()) null else ClassificationCandidate(entry.id, applied)
            }.sortedBy { it.id }
            val preserved = entries.any { it.id == original && it.active }
            val contradictory = candidates.any { it.id != original }
            val state = when {
                !product.identityValid -> ClassificationState.REVISAR
                preserved -> if (contradictory) ClassificationState.REVISAR else ClassificationState.ASIGNADO
                !original.isNullOrBlank() -> ClassificationState.REVISAR
                candidates.size > 1 -> ClassificationState.REVISAR
                candidates.size == 1 -> ClassificationState.ASIGNADO
                else -> ClassificationState.SIN_IDENTIFICAR
            }
            return ClassificationSuggestion(if (type == "category") "categoriaId" else "marcaId", original, state,
                if (preserved) original else if (state == ClassificationState.ASIGNADO) candidates.single().id else null, candidates, preserved,
                when {
                    !product.identityValid -> "Identidad o datos originales ambiguos: revisar producto, no escoger la primera coincidencia."
                    preserved && contradictory -> "Referencia existente válida conservada; el nombre contiene señales contradictorias."
                    preserved -> "Referencia existente activa y válida: se conserva."
                    !original.isNullOrBlank() -> "Referencia original inexistente/inactiva: requiere decisión individual."
                    candidates.size > 1 -> "Varias coincidencias de palabras completas; no se elige automáticamente."
                    candidates.size == 1 -> candidates.single().rules.joinToString("; ")
                    else -> "No hay una regla suficiente en los catálogos activos."
                })
        }
        return ClassifiedProduct(product, field("category", product.categoryId, catalog.categories), field("brand", product.brandId, catalog.brands), product.description.isNullOrBlank())
    }
}

/** Local review only. Approval does not authorize stock, create products or attest remote write permissions. */
class ClassificationReview(val fileSha256: String, val products: List<ClassificationProduct>, val catalog: ClassificationCatalog, val rules: ClassificationRules) {
    val classified = products.map { ProductClassifier.classify(it, catalog, rules) }
    private val byKey = classified.associateBy { it.product.key }
    private val itemKeys = classified.flatMap { p -> listOf(p.category, p.brand).map { f -> p.product.key to f.field } }
    private fun metadata(key: String, field: String): String = SyncExecutionContract.jsonValue(mapOf(
        "schemaVersion" to 1, "fileSha256" to fileSha256, "product" to productValue(byKey.getValue(key).product),
        "field" to field, "catalogDigest" to catalog.digest, "rulesDigest" to rules.digest, "rulesVersion" to rules.version,
        "suggestion" to suggestionValue(suggestion(key, field))))
    val journalCatalog = IssueReviewCatalog(fileSha256, ExcelInventory(emptyList(), itemKeys.map { (key, field) ->
        ExcelIssue("CLASIFICACION", null, metadata(key, field), ExcelIssueKind.OTHER, productName = key, serialCode = field)
    }, 0, 0))
    private val itemIndex by lazy { itemKeys.withIndex().associate { it.value to it.index } }
    private val itemsById by lazy { journalCatalog.items.withIndex().associate { it.value.id to it.index } }
    fun suggestion(key: String, field: String): ClassificationSuggestion = byKey.getValue(key).let {
        when(field) { "categoriaId" -> it.category; "marcaId" -> it.brand; else -> error("Campo no permitido") } }
    fun decision(key: String, field: String, id: String, reason: String, bulk: Boolean, uid: String, at: Long): IssueDecision {
        val p = byKey.getValue(key); val s = suggestion(key, field)
        require(p.product.identityValid && !s.preserved) { "No se modifica una referencia válida ni una identidad ambigua." }
        require(reason.isNotBlank()) { "Documenta la justificación." }
        val entries = if (field == "categoriaId") catalog.categories else catalog.brands
        require(entries.any { it.id == id && it.active }) { "El ID debe existir y estar activo en el catálogo cargado." }
        if (bulk) require(s.state == ClassificationState.ASIGNADO && s.proposed == id) { "Los conflictos requieren aprobación individual." }
        val index = requireNotNull(itemIndex[key to field])
        val item = journalCatalog.items[index]
        return IssueDecision(item.id, ExcelIssueKind.OTHER, journalCatalog.original(item), id, reason,
            "${if (bulk) "BULK" else "INDIVIDUAL"}:${catalog.digest}:${rules.digest}", uid, at, IssueReviewState.EN_REVISION, fileSha256)
    }
    fun current(journal: IssueReviewJournal): List<IssueDecision> = journal.latest.values.filter { it.issueId in itemsById }
    fun approved(journal: IssueReviewJournal, key: String, field: String): IssueDecision? = approved(journal.latest, key, field)
    fun approved(latest: Map<String, IssueDecision>, key: String, field: String): IssueDecision? = journalCatalog.items[itemIndex.getValue(key to field)].let { latest[it.id] }
    fun manifest(journal: IssueReviewJournal): Map<String, Any?> = mapOf("schemaVersion" to 1, "fileSha256" to fileSha256,
        "catalogDigest" to catalog.digest, "rulesDigest" to rules.digest, "rulesVersion" to rules.version,
        "products" to products.map(::productValue), "decisions" to current(journal).map { d ->
            val index = itemsById.getValue(d.issueId); val (key, field) = itemKeys[index]
            mapOf("productKey" to key, "field" to field, "id" to d.proposed, "reason" to d.reason,
                "mode" to if (d.evidence.startsWith("BULK:")) "BULK" else "INDIVIDUAL", "reviewer" to d.reviewer, "atMillis" to d.atMillis,
                "original" to d.original, "decisionId" to d.issueId) })
    companion object {
        fun productValue(p: ClassificationProduct): Map<String, Any?> = mapOf("key" to p.key, "documentId" to p.documentId, "sku" to p.sku,
            "name" to p.name, "categoryId" to p.categoryId, "brandId" to p.brandId, "description" to p.description, "identityValid" to p.identityValid)
        fun suggestionValue(s: ClassificationSuggestion): Map<String, Any?> = mapOf("field" to s.field, "original" to s.original, "state" to s.state.name,
            "proposed" to s.proposed, "preserved" to s.preserved, "candidates" to s.candidates.map { mapOf("id" to it.id, "rules" to it.rules) })
    }
}
