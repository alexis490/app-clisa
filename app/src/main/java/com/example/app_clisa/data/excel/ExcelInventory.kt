package com.example.app_clisa.data.excel

import java.math.BigDecimal
import java.text.Normalizer

enum class ExcelIssueKind { MISSING_COLUMNS, CELL_FORMAT, HEADER_REFERENCE_MISMATCH, MISSING_SKU, SKU_WHITESPACE, DUPLICATE_SKU,
    DUPLICATE_SERIAL, SERIAL_WHITESPACE, INVALID_QUANTITY, NEGATIVE_QUANTITY, SUMMARY_COUNT_MISMATCH,
    SUMMARY_QUANTITY_MISMATCH, SUMMARY_WITHOUT_DETAIL, UNINTERPRETED_ROW, UNASSOCIATED_SERIAL, PRODUCT_NAME_MISMATCH, OTHER }
data class ExcelIssue(val sheet: String, val row: Int?, val message: String, val kind: ExcelIssueKind = ExcelIssueKind.OTHER,
    val productCode: String? = null, val productName: String? = null, val serialCode: String? = null)
// code preserves the literal Excel value intended for existencias.codigo. No database IDs are invented.
data class ExcelSerial(val code: String, val quantity: Long?, val sheet: String, val row: Int, val valid: Boolean = true)
data class ExcelProduct(val code: String?, val name: String, val reportedQuantity: Long?, val serials: List<ExcelSerial>,
    val sheet: String, val row: Int, val reportedSerialCount: Int?)
data class ExcelStructure(val xlsxValidated: Boolean = false, val sheets: Int, val sheetsWithHeaders: Int,
    val nonEmptyRows: Int, val columnHeaderRows: Int, val productHeaderRows: Int, val serialRows: Int,
    val standaloneSummaryRows: Int, val uninterpretedRows: Int, val preambleRows: Int)
data class ExcelInventory(val products: List<ExcelProduct>, val issues: List<ExcelIssue>, val errorCount: Int, val summaryRows: Int,
    val structure: ExcelStructure? = null, val issueCounts: Map<ExcelIssueKind, Int> = emptyMap()) {
    val serialCount: Int get() = products.sumOf { product -> product.serials.count { it.valid } }
    val serialRowCount: Int get() = products.sumOf { it.serials.size }
    val uniqueSerialCount: Int get() = products.flatMap { it.serials }.map { it.code.trim() }.toSet().size
    val uniqueProductCodeCount: Int get() = products.mapNotNull { it.code }.toSet().size
    val uncodedProductCount: Int get() = products.count { it.code == null }
}

internal data class ExcelCell(val text: String, val number: String? = null, val problem: String? = null, val identifierProblem: String? = null)
internal data class ExcelRow(val number: Int, val cells: Map<Int, ExcelCell>)

/** Interprets grouped sales exports. It never adds summary quantities to serial quantities. */
internal class ExcelInventoryCollector {
    private data class Product(val code: String?, val name: String, val total: Long?, val sheet: String, val row: Int, val reportedCount: Int?,
        val serials: MutableList<ExcelSerial> = mutableListOf())
    private val products = mutableListOf<Product>()
    private val firstProductByCode = mutableMapOf<String, Product>()
    private val serials = mutableMapOf<String, MutableList<Pair<Product, Int>>>()
    private val issues = mutableListOf<ExcelIssue>()
    private var errors = 0
    private val issueCounts = mutableMapOf<ExcelIssueKind, Int>()
    private var summaries = 0
    private var columns: List<Int>? = null
    private var current: Product? = null
    private var sheet = ""
    private var foundHeader = false
    private var sheetCount = 0
    private var headedSheets = 0
    private var nonEmptyRows = 0
    private var columnHeaderRows = 0
    private var productHeaderRows = 0
    private var serialRows = 0
    private var standaloneSummaryRows = 0
    private var uninterpretedRows = 0
    private var preambleRows = 0
    private val groupSuffix = Regex("^(.*) \\(([0-9]+)\\)$")

    /** Balanced brackets also preserve SKUs such as 4408C001[AA]. */
    private fun productLabel(value: String): Pair<String, String>? {
        val text = value.trim()
        if (!text.startsWith("[")) return null
        var depth = 0
        for (index in text.indices) {
            if (text[index] == '[') depth++
            if (text[index] == ']' && --depth == 0) {
                val code = text.substring(1, index)
                val name = text.substring(index + 1).trim()
                return if (code.isNotBlank() && name.isNotEmpty()) code to name else null
            }
        }
        return null
    }

    fun startSheet(name: String) { sheetCount++; sheet = name; columns = null; current = null; foundHeader = false }
    private fun issue(row: Int?, message: String, kind: ExcelIssueKind = ExcelIssueKind.OTHER,
        sku: String? = current?.code, name: String? = current?.name, serial: String? = null) {
        errors++
        issueCounts[kind] = issueCounts.getOrDefault(kind, 0) + 1
        // Keep all real-file diagnostics, with an explicit bound for hostile files. Counts are never truncated.
        if (issues.size < 10_000) issues += ExcelIssue(sheet, row, message, kind, sku, name, serial)
    }
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    fun row(row: ExcelRow) {
        val cells = row.cells
        if (cells.values.all { it.text.isBlank() && it.problem == null }) return
        nonEmptyRows++
        val names = cells.mapValues { normalize(it.value.text) }
        val header = listOf(setOf("lotendeserie", "lotenumerodeserie"), setOf("referenciainterna"), setOf("producto"), setOf("cantidad"))
            .map { aliases -> names.filterValues { it in aliases }.keys.singleOrNull() }
        if (header.all { it != null }) {
            columnHeaderRows++
            if (!foundHeader) headedSheets++
            columns = header.filterNotNull(); foundHeader = true; current = null; return
        }
        val cols = columns ?: run { preambleRows++; return } // Preserve reading behavior; track unknown pre-table content.
        val relevant = cols.map { cells[it] ?: ExcelCell("") }
        val problems = relevant.mapNotNull { it.problem } + relevant.take(3).mapNotNull { it.identifierProblem }
        if (problems.isNotEmpty()) {
            uninterpretedRows++
            problems.distinct().forEach { issue(row.number, it, ExcelIssueKind.CELL_FORMAT) }
            // An unreadable heading must not lend the previous product to its children.
            // A bad serial quantity, however, must not invalidate later independent rows.
            if (relevant[0].text.isBlank() || relevant.take(3).any { it.text.trim().startsWith("[") }) current = null
            return
        }
        val (lot, reference, product, quantity) = relevant
        val totalLabels = setOf("total", "totalgeneral", "subtotal")
        if (reference.text.isBlank() && ((normalize(lot.text) in totalLabels && product.text.isBlank()) ||
                (lot.text.isBlank() && normalize(product.text) in totalLabels))) {
            summaries++; standaloneSummaryRows++; current = null
            if (quantity.text.isNotBlank()) quantity(quantity, row.number, null, null)
            return
        }
        val detailLabel = productLabel(product.text)
        val suffix = groupSuffix.matchEntire(lot.text.trim())?.takeIf { product.text.isBlank() && reference.text.isBlank() }
        val headingText = suffix?.groupValues?.get(1) ?: lot.text.trim()
        val lotLabel = productLabel(headingText)
        val heading = lotLabel ?: detailLabel?.takeIf { lot.text.isBlank() }
        if (heading != null || suffix != null) {
            summaries++
            val code = heading?.first
            if (code != null && code != code.trim()) issue(row.number,
                "El SKU contiene espacios exteriores. Se conserva literalmente y requiere revisión.", ExcelIssueKind.SKU_WHITESPACE, code, heading?.second)
            if (code != null && reference.text.isNotBlank() && reference.text.trim() != code) {
                uninterpretedRows++; issue(row.number, "Referencia Interna no coincide con el código [$code].", ExcelIssueKind.HEADER_REFERENCE_MISMATCH, code, heading?.second); current = null; return
            }
            val total = if (quantity.text.isBlank()) null else quantity(quantity, row.number, code, heading?.second ?: headingText)
            val existing = code?.let(firstProductByCode::get)
            if (existing != null) issue(row.number, "Código de producto duplicado: $code (primero en ${existing.sheet}, fila ${existing.row}).", ExcelIssueKind.DUPLICATE_SKU, code, heading?.second ?: headingText)
            if (code == null) issue(row.number, "Grupo sin código de producto entre corchetes. Se conservan su nombre y series para revisión.", ExcelIssueKind.MISSING_SKU, null, headingText)
            // Duplicate SKUs may have different descriptions: never merge their groups or choose a winner.
            current = Product(code, heading?.second ?: headingText, total, sheet, row.number,
                suffix?.groupValues?.get(2)?.toIntOrNull()).also {
                products += it
                if (code != null && existing == null) firstProductByCode[code] = it
            }
            productHeaderRows++
            return
        }
        val explicitCode = detailLabel?.first ?: reference.text.trim().takeIf { it.isNotEmpty() }
        val owner = current?.takeIf { explicitCode == null || explicitCode == it.code }
        if (lot.text.isBlank()) {
            uninterpretedRows++
            issue(row.number, "Fila sin número de serie ni encabezado [código] interpretable.", ExcelIssueKind.UNINTERPRETED_ROW); current = null; return
        }
        if (owner == null) { uninterpretedRows++; issue(row.number, "La serie ${lot.text} no tiene un encabezado de producto reconocido.", ExcelIssueKind.UNASSOCIATED_SERIAL, serial = lot.text); return }
        if (product.text.isNotBlank() && (detailLabel?.second ?: product.text.trim()) != owner.name) {
            uninterpretedRows++
            issue(row.number, "El nombre del producto no coincide con el encabezado [${owner.code}].", ExcelIssueKind.PRODUCT_NAME_MISMATCH, serial = lot.text); return
        }
        val code = lot.text
        val key = code.trim()
        val occurrences = serials.getOrPut(key) { mutableListOf() }
        if (occurrences.isNotEmpty()) {
            val (firstOwner, firstIndex) = occurrences.first()
            val first = firstOwner.serials[firstIndex]
            issue(row.number, "Número de serie duplicado: $key (primero en ${first.sheet}, fila ${first.row}).", ExcelIssueKind.DUPLICATE_SERIAL, serial = code)
            occurrences.forEach { (parent, index) -> parent.serials[index] = parent.serials[index].copy(valid = false) }
        }
        val whitespace = code != key
        if (whitespace) issue(row.number, "La serie '$code' contiene espacios al inicio o al final. Se conserva el texto original; requiere revisión.", ExcelIssueKind.SERIAL_WHITESPACE, serial = code)
        val count = quantity(quantity, row.number, serial = code)
        val valid = count != null && count >= 0 && occurrences.isEmpty() && !whitespace
        occurrences += owner to owner.serials.size
        owner.serials += ExcelSerial(code, count, sheet, row.number, valid)
        serialRows++
        current = owner
    }

    private fun quantity(cell: ExcelCell, row: Int, sku: String? = current?.code, name: String? = current?.name, serial: String? = null): Long? {
        return try {
            val raw = cell.number ?: cell.text.trim().takeIf { it.matches(Regex("[0-9]+")) }
                ?: throw IllegalArgumentException()
            BigDecimal(raw).longValueExact().also { if (it < 0) issue(row, "Cantidad negativa: '${cell.text}'. Se conserva para revisión, no es válida para importar.", ExcelIssueKind.NEGATIVE_QUANTITY, sku, name, serial) }
        } catch (_: Exception) { issue(row, "Cantidad inválida: '${cell.text}'. Debe ser un entero no negativo sin separadores de miles.", ExcelIssueKind.INVALID_QUANTITY, sku, name, serial); null }
    }

    fun endSheet() {
        if (!foundHeader) issue(null, "No se encontraron las cuatro columnas: Lote/Nº de serie, Referencia Interna, Producto y Cantidad.", ExcelIssueKind.MISSING_COLUMNS, null, null)
    }
    fun result(): ExcelInventory {
        for (product in products) {
            sheet = product.sheet
            if (product.reportedCount != null && product.reportedCount != product.serials.size)
                issue(product.row, "El grupo indica ${product.reportedCount} filas de serie, pero se interpretaron ${product.serials.size}.", ExcelIssueKind.SUMMARY_COUNT_MISMATCH, product.code, product.name)
            if (product.serials.isEmpty() && (product.total ?: 0) > 0) {
                sheet = product.sheet; issue(product.row, "El producto ${product.code} tiene cantidad de resumen pero no series válidas.", ExcelIssueKind.SUMMARY_WITHOUT_DETAIL, product.code, product.name)
            } else if (product.total != null && product.serials.isNotEmpty() && product.serials.all { it.quantity != null }) {
                // Reconcile the source rows, including flagged duplicates/negatives; do not add the summary a second time.
                val sum = product.serials.fold(BigDecimal.ZERO) { total, serial -> total + BigDecimal.valueOf(requireNotNull(serial.quantity)) }
                if (sum != BigDecimal.valueOf(product.total)) {
                    sheet = product.sheet; issue(product.row, "La cantidad del resumen de ${product.code} (${product.total}) no coincide con sus series ($sum).", ExcelIssueKind.SUMMARY_QUANTITY_MISMATCH, product.code, product.name)
                }
            }
        }
        return ExcelInventory(products.map { ExcelProduct(it.code, it.name, it.total, it.serials.toList(), it.sheet, it.row, it.reportedCount) }, issues.toList(), errors, summaries,
            ExcelStructure(false, sheetCount, headedSheets, nonEmptyRows, columnHeaderRows, productHeaderRows, serialRows,
                standaloneSummaryRows, uninterpretedRows, preambleRows), issueCounts.toMap())
    }
}
