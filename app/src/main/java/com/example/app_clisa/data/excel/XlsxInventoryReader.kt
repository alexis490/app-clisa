package com.example.app_clisa.data.excel

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.File
import java.io.FilterInputStream
import java.math.BigDecimal
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/** Bounded, read-only SpreadsheetML reader. No formulas, links or macros are executed. */
class XlsxInventoryReader {
    companion object { const val MAX_FILE_BYTES = 25L * 1024 * 1024 }
    private data class Relation(val target: String, val type: String)

    fun read(file: File, checkCancelled: () -> Unit = {}): ExcelInventory {
        require(file.length() <= MAX_FILE_BYTES) { "El archivo supera el límite de 25 MB." }
        try {
            ZipFile(file).use { zip ->
                val names = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(names.add(entry.name) && names.size <= 2000) { "El Excel tiene una estructura ZIP no admitida." }
                    require(!entry.name.endsWith("vbaProject.bin", true)) { "Selecciona un archivo .xlsx sin macros." }
                }
                require("xl/workbook.xml" in names && "[Content_Types].xml" in names) { "El archivo no es un Excel .xlsx válido." }
                var readBytes = 0L
                fun parse(path: String, handler: Handler) {
                    checkCancelled()
                    val entry = zip.getEntry(path) ?: throw IllegalArgumentException("Falta una parte del Excel: $path.")
                    require(entry.size <= 150L * 1024 * 1024) { "El contenido descomprimido del Excel es demasiado grande." }
                    zip.getInputStream(entry).use { input ->
                        val bounded = object : FilterInputStream(input) {
                            private fun count(n: Int): Int {
                                if (n > 0) readBytes += n
                                require(readBytes <= 150L * 1024 * 1024) { "El contenido descomprimido del Excel supera los 150 MB." }
                                checkCancelled(); return n
                            }
                            override fun read(): Int { val value = `in`.read(); count(if (value < 0) 0 else 1); return value }
                            override fun read(b: ByteArray, off: Int, len: Int): Int = count(`in`.read(b, off, len))
                        }
                        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
                        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
                        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                        reader.contentHandler = handler; reader.entityResolver = handler
                        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
                        reader.parse(InputSource(bounded))
                    }
                }
                val relations = mutableMapOf<String, Relation>()
                parse("xl/_rels/workbook.xml.rels", object : Handler() {
                    override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                        if (local == "Relationship" && a.getValue("TargetMode") != "External") {
                            val rawTarget = a.getValue("Target")
                            val target = if (rawTarget.startsWith("/")) URI(rawTarget.removePrefix("/")).normalize()
                                else URI("xl/").resolve(rawTarget).normalize()
                            require(!target.isAbsolute && target.path.startsWith("xl/") && !target.path.contains("..")) { "Ruta interna de Excel no admitida." }
                            require(relations.size < 2000) { "Demasiadas relaciones en el Excel." }
                            relations[a.getValue("Id")] = Relation(target.path, a.getValue("Type"))
                        }
                    }
                })
                val sheets = mutableListOf<Pair<String, String>>()
                parse("xl/workbook.xml", object : Handler() {
                    override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                        if (local == "sheet") {
                            require(sheets.size < 20) { "El Excel supera el máximo de 20 hojas." }
                            val id = (0 until a.length).firstOrNull { a.getLocalName(it) == "id" }?.let(a::getValue)
                            val relation = relations[id] ?: throw IllegalArgumentException("La hoja no tiene una relación interna válida.")
                            require(relation.type.endsWith("/worksheet")) { "El libro contiene una hoja no compatible." }
                            sheets += a.getValue("name") to relation.target
                        }
                    }
                })
                require(sheets.isNotEmpty()) { "El Excel no contiene hojas de cálculo." }
                val shared = mutableListOf<String>()
                var sharedCharacters = 0L
                relations.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.let { relation ->
                    parse(relation.target, object : Handler() {
                        private var text = false
                        private var phonetic = false
                        private val value = StringBuilder()
                        override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                            if (local == "si") value.setLength(0)
                            if (local == "rPh") phonetic = true
                            if (local == "t" && !phonetic) text = true
                        }
                        override fun characters(ch: CharArray, start: Int, length: Int) {
                            if (text) { value.append(ch, start, length); require(value.length <= 32767) { "Celda demasiado larga." } }
                        }
                        override fun endElement(uri: String, local: String, q: String) {
                            if (local == "t") text = false
                            if (local == "rPh") phonetic = false
                            if (local == "si") {
                                sharedCharacters += value.length
                                require(shared.size < 500_000 && sharedCharacters <= 20_000_000) { "El Excel contiene demasiados textos para leerlo en este dispositivo." }
                                shared += value.toString()
                            }
                        }
                    })
                }
                val formats = mutableMapOf<Int, String>(0 to "General", 1 to "0", 49 to "@")
                val styles = mutableListOf<Int>()
                relations.values.firstOrNull { it.type.endsWith("/styles") }?.let { relation ->
                    parse(relation.target, object : Handler() {
                        private var cellFormats = false
                        override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                            if (local == "numFmt") { require(formats.size < 100_000) { "Demasiados formatos de celda." }; formats[a.getValue("numFmtId").toInt()] = a.getValue("formatCode") }
                            if (local == "cellXfs") cellFormats = true
                            if (local == "xf" && cellFormats) {
                                require(styles.size < 100_000) { "Demasiados estilos de celda." }
                                styles += a.getValue("numFmtId")?.toIntOrNull() ?: 0
                            }
                        }
                        override fun endElement(uri: String, local: String, q: String) { if (local == "cellXfs") cellFormats = false }
                    })
                }
                val collector = ExcelInventoryCollector()
                var rowCount = 0
                sheets.forEach { (name, path) ->
                    collector.startSheet(name)
                    parse(path, object : Handler() {
                        private var row = 0
                        private var col = -1
                        private var style = 0
                        private var type = ""
                        private var formula = false
                        private var text = false
                        private var readingValue = false
                        private var phonetic = false
                        private var inCell = false
                        private val value = StringBuilder()
                        private val inline = StringBuilder()
                        private val cells = mutableMapOf<Int, ExcelCell>()
                        override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                            when (local) {
                                "row" -> {
                                    require(++rowCount <= 250_000) { "El Excel supera las 250 000 filas." }
                                    row = a.getValue("r")?.toIntOrNull() ?: (row + 1); cells.clear(); col = -1
                                }
                                "c" -> {
                                    inCell = true
                                    val address = a.getValue("r")
                                    col = address?.takeWhile { it in 'A'..'Z' }?.fold(0) { n, c -> n * 26 + c.code - 64 }?.minus(1) ?: (col + 1)
                                    require(col in 0..16383 && cells.size < 1024) { "Demasiadas columnas o referencia de celda inválida." }
                                    type = a.getValue("t").orEmpty(); style = a.getValue("s")?.toIntOrNull() ?: 0
                                    value.setLength(0); inline.setLength(0); formula = false
                                }
                                "f" -> if (inCell) formula = true
                                "v" -> if (inCell) readingValue = true
                                "rPh" -> phonetic = true
                                "t" -> if (inCell && !phonetic) text = true
                            }
                        }
                        override fun characters(ch: CharArray, start: Int, length: Int) {
                            if (readingValue) value.append(ch, start, length)
                            if (text) inline.append(ch, start, length)
                            require(value.length <= 32767 && inline.length <= 32767) { "Celda demasiado larga." }
                        }
                        override fun endElement(uri: String, local: String, q: String) {
                            when (local) {
                                "v" -> readingValue = false
                                "t" -> text = false
                                "rPh" -> phonetic = false
                                "c" -> {
                                    val raw = value.toString()
                                    val cell = when {
                                        formula -> ExcelCell(raw, problem = "La fila contiene una fórmula. Exporta sus valores antes de leerla.")
                                        type == "s" -> ExcelCell(shared.getOrNull(raw.toIntOrNull() ?: -1)
                                            ?: throw IllegalArgumentException("Referencia de texto compartido inválida."))
                                        type == "inlineStr" -> ExcelCell(inline.toString())
                                        type == "str" -> ExcelCell(raw)
                                        raw.isEmpty() -> ExcelCell("")
                                        type.isEmpty() || type == "n" -> numericCell(raw, formats[styles.getOrNull(style) ?: 0])
                                        else -> ExcelCell(raw, problem = "Tipo de celda no admitido ($type). Exporta códigos y cantidades como valores.")
                                    }
                                    require(!cells.containsKey(col)) { "Celda duplicada en la fila $row." }
                                    cells[col] = cell; inCell = false
                                }
                                "row" -> { checkCancelled(); collector.row(ExcelRow(row, cells.toMap())) }
                            }
                        }
                    })
                    collector.endSheet()
                }
                val result = collector.result()
                return result.copy(structure = result.structure?.copy(xlsxValidated = true))
            }
        } catch (e: java.util.zip.ZipException) { throw IllegalArgumentException("No se pudo abrir el .xlsx. Comprueba que no esté dañado, cifrado o guardado como .xls.", e) }
    }

    private fun numericCell(raw: String, format: String?): ExcelCell {
        val number = raw.toBigDecimalOrNull() ?: return ExcelCell(raw, problem = "Valor numérico inválido.")
        if (number.precision() > 15 || number.precision().toLong() - number.scale() > 15 || number.scale() !in -64..64)
            return ExcelCell(raw, raw, problem = "Número fuera de rango o con más de 15 dígitos: expórtalo como texto para conservar su precisión.")
        val plain = number.stripTrailingZeros().toPlainString()
        val mask = format?.takeIf { it.matches(Regex("0{1,64}")) }
        val text = if (mask != null && number.scale() <= 0) plain.padStart(mask.length, '0')
            else if (mask != null && number.stripTrailingZeros().scale() <= 0) plain.padStart(mask.length, '0') else plain
        // The numeric raw value remains separate from the displayed identifier.
        return ExcelCell(text, raw, identifierProblem =
            if (format != "General" && format != "@" && mask == null) "Código numérico con formato no compatible. Expórtalo como texto para conservarlo exactamente." else null)
    }

    private open class Handler : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("No se admiten declaraciones DTD en Excel.") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("No se admiten entidades externas.") }
    }
}
