package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.*

class ImportFinalizationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun workbook(file: File, qty: Long, extra: String = "") {
        fun text(address: String, value: String) = """<c r="$address" t="inlineStr"><is><t xml:space="preserve">$value</t></is></c>"""
        fun row(n: Int, vararg cells: String) = """<row r="$n">${cells.joinToString("")}</row>"""
        fun number(a: String, q: Long) = """<c r="$a"><v>$q</v></c>"""
        val headers = row(1, text("A1", "Lote/Nº de serie"), text("B1", "Referencia Interna"), text("C1", "Producto"), text("D1", "Cantidad"))
        val rows = headers + row(2, text("A2", "[SKU/1] Producto ficticio"), number("D2", qty)) +
            row(3, text("A3", "0001"), text("B3", "SKU/1"), number("D3", qty)) + extra
        val parts = mapOf("[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Excel" sheetId="1" r:id="r1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""")
        ZipOutputStream(file.outputStream()).use { zip -> parts.forEach { (name, xml) -> zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray()); zip.closeEntry() } }
    }
    private fun fixture(file: File, stock: Long = 5): Triple<ExcelInventory, InventoryComparison, SyncPlan> {
        val excel = XlsxInventoryReader().read(file)
        val snapshot = InventoryReadSnapshot(listOf(ComparedProduct("p1", "SKU/1", "Producto ficticio", stock, 99, "Timestamp(seconds=1, nanoseconds=0)")),
            listOf(ComparedExistence("e-p1", "p1", "SKU/1", "0001", stock, "DISPONIBLE", "Timestamp(seconds=1, nanoseconds=0)")), 1, System.currentTimeMillis())
        val c = InventoryComparator.compare(excel, snapshot)
        val sha = file.inputStream().use { SyncPlanIdentity.fileSha256(it) }
        val temporal = ExportTemporalReviewer.review(ManualExportTime(java.time.LocalDate.of(1970, 1, 1), java.time.LocalTime.of(0, 0)), nowMillis = System.currentTimeMillis())
        return Triple(excel, c, InventoryDryRunPlanner.generate(c, ReportReadEvidence.fromReadResult(excel, file.name, sha, temporal)))
    }
    @Test fun actualXlsxReaderComparisonPlanAndOfflinePackageAreLinked() {
        val file = temporary.newFile("input.xlsx"); workbook(file, 6)
        val original = file.readBytes(); val (excel, c, plan) = fixture(file)
        assertEquals(0, excel.errorCount); assertEquals(1, c.differentQuantities.size)
        val output = ByteArrayOutputStream()
        ImportFinalization.writePackage(file, output, plan.fileSha256!!, SyncExecutionContract.json(plan, excel, c, "ADMIN"), "{}")
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip -> while (true) { val e = zip.nextEntry ?: break; entries[e.name] = zip.readBytes() } }
        assertEquals(setOf("source.xlsx", "contract.json", "review.json"), entries.keys)
        assertArrayEquals(original, entries["source.xlsx"]); assertArrayEquals(original, file.readBytes())
        val summary = ImportFinalization.summarize(plan, excel); assertEquals(2, summary.verified); assertFalse(summary.productionExecutable)
    }
    @Test fun changedSourceCannotBeExportedAsReviewedBytes() {
        val file = temporary.newFile(); workbook(file, 5); val (_, _, plan) = fixture(file); file.appendText("changed")
        val output = ByteArrayOutputStream()
        try { ImportFinalization.writePackage(file, output, plan.fileSha256!!, "{}", "{}"); fail("Hash mismatch") } catch (_: IllegalArgumentException) {}
        assertEquals(0, output.size())
    }
    @Test fun emitsFullPipelineFixturesFromTheActualReader() {
        val folder = System.getenv("CLISA_FINAL_FIXTURE_DIR")?.let { File(it).also { it.mkdirs() } } ?: temporary.newFolder()
        val extras = mapOf(
            "duplicate" to """<row r="4"><c r="A4" t="inlineStr"><is><t>0001</t></is></c><c r="B4" t="inlineStr"><is><t>SKU/1</t></is></c><c r="D4"><v>1</v></c></row>""",
            "incomplete" to """<row r="4"><c r="A4" t="inlineStr"><is><t>Producto sin SKU (1)</t></is></c><c r="D4"><v>1</v></c></row><row r="5"><c r="A5" t="inlineStr"><is><t>UNKNOWN</t></is></c><c r="D5"><v>1</v></c></row>""",
            "local-issue" to """<row r="4"><c r="A4" t="inlineStr"><is><t>[SKU/2] Otro producto</t></is></c><c r="D4"><v>-1</v></c></row><row r="5"><c r="A5" t="inlineStr"><is><t>OTHER</t></is></c><c r="B5" t="inlineStr"><is><t>SKU/2</t></is></c><c r="D5"><v>-1</v></c></row>""")
        for (name in listOf("unchanged", "valid", "pending", "completed", "ambiguous", "duplicate", "incomplete", "local-issue", "new", "retained-absence")) {
            val file = File(folder, "$name.xlsx"); val qty = when(name) { "unchanged" -> 5L; "pending", "ambiguous" -> 10L; "completed" -> 8L; else -> 6L }
            workbook(file, qty, extras[name] ?: "")
            val (excel, initial, _) = fixture(file, if (name in listOf("pending", "completed")) 7 else 5)
            val baseline = when(name) {
                "new" -> InventoryReadSnapshot(emptyList(), emptyList(), 1, System.currentTimeMillis())
                "retained-absence" -> initial.inventory!!.copy(existences = initial.inventory!!.existences +
                    ComparedExistence("retained", "p1", "SKU/1", "MISSING", 4, "DISPONIBLE", "Timestamp(seconds=1, nanoseconds=0)"))
                else -> initial.inventory!!
            }
            val compared = InventoryComparator.compare(excel, baseline)
            val sha = file.inputStream().use { SyncPlanIdentity.fileSha256(it) }
            val plan = InventoryDryRunPlanner.generate(compared, ReportReadEvidence.fromReadResult(excel, file.name, sha))
            File(folder, "$name.json").writeText(SyncExecutionContract.json(plan, excel, compared, "ADMIN"))
            assertTrue(file.length() > 0)
            if (name in extras) assertTrue(excel.errorCount > 0)
        }
    }
}
