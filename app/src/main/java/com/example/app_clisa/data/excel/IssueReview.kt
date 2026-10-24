package com.example.app_clisa.data.excel

/** Reviewed decisions are evidence, never corrections or authorization to execute a plan. */
enum class IssueReviewState { PENDIENTE, EN_REVISION, RESUELTO, BLOQUEADO }
data class IssueReviewItem(val id: String, val issue: ExcelIssue)
data class IssueDecision(val issueId: String, val kind: ExcelIssueKind, val original: String,
    val proposed: String, val reason: String, val evidence: String, val reviewer: String,
    val atMillis: Long, val state: IssueReviewState, val sourceSha256: String)
data class IssueReviewJournal(val sourceSha256: String, val events: List<IssueDecision> = emptyList()) {
    val revision: Int get() = events.size
    val latest: Map<String, IssueDecision> get() = events.associateBy { it.issueId }
}
data class IssueReviewCounts(val total: Int, val pending: Int, val reviewed: Int, val resolved: Int,
    val blocked: Int, val inReview: Int, val appliedToCopy: Int = 0)
data class IssueReviewedDryRun(val plan: SyncPlan, val review: IssueReviewCounts) {
    val productionExecutable: Boolean get() = false
    val warning: String get() = "Revisar incidencias no modifica el plan ni verifica la integridad o el corte temporal del Excel."
}
data class IssueOccurrence(val sheet: String, val row: Int, val productCode: String?, val name: String,
    val serial: String?, val quantity: Long?, val documentId: String? = null)
data class ReviewedCopyPreparation(val approved: List<IssueDecision>, val blockedReason: String) {
    val canGenerate: Boolean get() = false
    fun generate(): Nothing = error(blockedReason)
}

class IssueReviewCatalog(val sourceSha256: String, val excel: ExcelInventory,
    private val baseline: InventoryReadSnapshot? = null) {
    init { require(Regex("[a-f0-9]{64}").matches(sourceSha256)) }
    val items = excel.issues.mapIndexed { index, issue ->
        val wire = mapOf("sheet" to issue.sheet, "row" to issue.row, "message" to issue.message,
            "kind" to issue.kind.name, "productCode" to issue.productCode, "productName" to issue.productName,
            "serialCode" to issue.serialCode)
        IssueReviewItem(SyncCanonical.hash(listOf("CLISA_ISSUE_REVIEW_V1", sourceSha256, index, wire)), issue)
    }
    private val byId = items.associateBy { it.id }
    private val products = excel.products.groupBy { it.code?.trim() }
    private val serials = excel.products.flatMap { p -> p.serials.map { p to it } }.groupBy { it.second.code.trim() }
    private val dbProducts = baseline?.products.orEmpty().groupBy { it.code?.trim() }
    private val dbSerials = baseline?.existences.orEmpty().groupBy { it.code?.trim() }

    fun original(item: IssueReviewItem): String = when (item.issue.kind) {
        ExcelIssueKind.MISSING_SKU -> "SKU ausente: ${item.issue.productName.orEmpty()}"
        ExcelIssueKind.SERIAL_WHITESPACE, ExcelIssueKind.DUPLICATE_SERIAL -> item.issue.serialCode.orEmpty()
        ExcelIssueKind.NEGATIVE_QUANTITY -> occurrences(item).filter { it.sheet == item.issue.sheet && it.row == item.issue.row }
            .joinToString { it.quantity?.toString() ?: "desconocida" }
        else -> item.issue.productCode ?: item.issue.message
    }
    fun suggestion(item: IssueReviewItem): String = if (item.issue.kind == ExcelIssueKind.SERIAL_WHITESPACE)
        item.issue.serialCode.orEmpty().trim() else ""

    /** Includes FIRST occurrences and Firestore IDs; no selection of a winner or merge. */
    fun occurrences(item: IssueReviewItem): List<IssueOccurrence> {
        val i = item.issue
        val rows = when (i.kind) {
            ExcelIssueKind.DUPLICATE_SERIAL, ExcelIssueKind.SERIAL_WHITESPACE ->
                serials[i.serialCode?.trim()].orEmpty().map { (p, s) -> IssueOccurrence(s.sheet, s.row, p.code, p.name, s.code, s.quantity) }
            ExcelIssueKind.DUPLICATE_SKU -> products[i.productCode?.trim()].orEmpty().map {
                IssueOccurrence(it.sheet, it.row, it.code, it.name, null, it.reportedQuantity) }
            else -> excel.products.filter { p -> p.sheet == i.sheet && (p.row == i.row || p.serials.any { it.row == i.row }) }
                .flatMap { p -> listOf(IssueOccurrence(p.sheet, p.row, p.code, p.name, null, p.reportedQuantity)) +
                    p.serials.map { s -> IssueOccurrence(s.sheet, s.row, p.code, p.name, s.code, s.quantity) } }
        }
        val db = if (i.serialCode != null) dbSerials[i.serialCode.trim()].orEmpty().map {
            IssueOccurrence("Firestore", 0, it.productCode, "productoId=${it.productId}", it.code, it.quantity, it.id)
        } else dbProducts[i.productCode?.trim()].orEmpty().map {
            IssueOccurrence("Firestore", 0, it.code, it.name.orEmpty(), null, it.stock, it.id)
        }
        return rows + db
    }

    fun filter(journal: IssueReviewJournal, kind: ExcelIssueKind? = null, state: IssueReviewState? = null,
        product: String = ""): List<IssueReviewItem> {
        check(journal.sourceSha256 == sourceSha256)
        val latest = journal.latest
        val words = product.trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return items.filter { item -> (kind == null || item.issue.kind == kind) &&
            (state == null || (latest[item.id]?.state ?: IssueReviewState.PENDIENTE) == state) &&
            words.all { word -> listOf(item.issue.productCode, item.issue.productName, item.issue.serialCode, item.issue.row?.toString())
                .joinToString(" ").lowercase(java.util.Locale.ROOT).contains(word) } }
    }
    fun counts(journal: IssueReviewJournal): IssueReviewCounts {
        require(journal.sourceSha256 == sourceSha256)
        val latest = journal.latest
        val states = items.map { latest[it.id]?.state ?: IssueReviewState.PENDIENTE }
        val pending = states.count { it == IssueReviewState.PENDIENTE }
        return IssueReviewCounts(items.size, pending, states.size - pending, states.count { it == IssueReviewState.RESUELTO },
            states.count { it == IssueReviewState.BLOQUEADO }, states.count { it == IssueReviewState.EN_REVISION })
    }
    fun reviewDryRun(plan: SyncPlan, journal: IssueReviewJournal): IssueReviewedDryRun {
        require(plan.fileSha256 == sourceSha256) { "El Dry Run pertenece a otro Excel." }
        return IssueReviewedDryRun(plan, counts(journal))
    }

    fun validate(decision: IssueDecision, journal: IssueReviewJournal) {
        val item = requireNotNull(byId[decision.issueId]) { "Incidencia ajena al archivo." }
        require(journal.sourceSha256 == sourceSha256 && decision.sourceSha256 == sourceSha256) { "El hash cambió; selecciona y revisa el archivo nuevo." }
        require(decision.kind == item.issue.kind && decision.original == original(item)) { "El valor original cambió." }
        require(decision.reviewer.isNotBlank() && decision.atMillis > 0)
        require(listOf(decision.proposed, decision.reason, decision.evidence).all { it.length <= 4000 })
        if (decision.state == IssueReviewState.PENDIENTE) return
        require(decision.reason.isNotBlank()) { "Documenta la justificación." }
        if (decision.state != IssueReviewState.RESUELTO) return
        require(decision.evidence.isNotBlank()) { "Falta evidencia del sistema principal." }
        val proposed = decision.proposed
        when (item.issue.kind) {
            ExcelIssueKind.MISSING_SKU -> {
                require(proposed.isNotBlank() && proposed == proposed.trim()) { "Propón un SKU explícito sin espacios exteriores." }
                require(item.issue.productName?.isNotBlank() == true && occurrences(item).isNotEmpty()) { "Contexto insuficiente: mantener bloqueado." }
                require(products[proposed].isNullOrEmpty() && dbProducts[proposed].isNullOrEmpty()) { "El SKU colisiona con otro producto; no se asignó ni fusionó." }
                require(journal.latest.values.none { it.issueId != decision.issueId && it.state == IssueReviewState.RESUELTO &&
                    it.kind == ExcelIssueKind.MISSING_SKU && it.proposed == proposed }) { "SKU ya propuesto para otro grupo." }
                require(baseline?.complete == true) { "Falta la comparación completa para verificar colisiones con Firestore." }
            }
            ExcelIssueKind.SERIAL_WHITESPACE -> {
                require(proposed.isNotBlank() && proposed == suggestion(item)) { "Solo se admite el recorte explícito del literal original." }
                require(serials[proposed].orEmpty().size == 1) { "La serie normalizada colisiona con otra fila Excel." }
                val existing = dbSerials[proposed].orEmpty()
                require(existing.isEmpty() || (existing.size == 1 && existing.single().code == item.issue.serialCode &&
                    existing.single().productCode == item.issue.productCode)) { "La serie normalizada colisiona con otra identidad Firestore." }
                require(baseline?.complete == true) { "Falta la comparación completa para verificar series Firestore." }
            }
            ExcelIssueKind.NEGATIVE_QUANTITY -> require(proposed.toLongOrNull()?.let { it >= 0 } == true) { "Indica una cantidad entera no negativa respaldada por evidencia." }
            ExcelIssueKind.DUPLICATE_SERIAL, ExcelIssueKind.DUPLICATE_SKU -> {
                require(proposed in listOf("REPETICION_LEGITIMA", "CORREGIR_EN_ORIGEN")) { "Documenta REPETICION_LEGITIMA o CORREGIR_EN_ORIGEN; no se fusionan filas." }
                require(occurrences(item).count { it.sheet != "Firestore" } >= 2) { "No se recuperaron todas las apariciones; mantener bloqueado." }
            }
            else -> error("Este tipo requiere revisión técnica; conserva EN_REVISION o BLOQUEADO.")
        }
    }
    fun prepareCopy(journal: IssueReviewJournal): ReviewedCopyPreparation {
        require(journal.sourceSha256 == sourceSha256)
        val approved = journal.latest.values.filter { it.state == IssueReviewState.RESUELTO }
        approved.forEach { validate(it, journal) }
        return ReviewedCopyPreparation(approved, "Copia XLSX bloqueada: el lector actual no conserva la estructura para una escritura fiable. Corrige en el sistema principal y vuelve a exportar; se leerá y validará desde cero con un hash nuevo.")
    }
}
