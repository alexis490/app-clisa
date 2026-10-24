package com.example.app_clisa

import com.example.app_clisa.data.excel.XlsxInventoryReader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxInventoryReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val headers = row(1, text("A1", "Lote/Nº de serie"), text("B1", "Referencia Interna"), text("C1", "Producto"), text("D1", "Cantidad"))
    private fun text(address: String, value: String) = """<c r="$address" t="inlineStr"><is><t>$value</t></is></c>"""
    private fun number(address: String, value: String, style: Int = 0) = """<c r="$address" s="$style"><v>$value</v></c>"""
    private fun row(index: Int, vararg cells: String) = """<row r="$index">${cells.joinToString("")}</row>"""
    private fun heading(index: Int = 2, code: String = "0001", quantity: String = "2") = row(index, text("A$index", "[$code] Parlante"), number("D$index", quantity))
    private fun serial(index: Int, code: String, quantity: String = "1", reference: String = "0001") =
        row(index, text("A$index", code), text("B$index", reference), number("D$index", quantity))

    private fun workbook(rows: String, extras: Map<String, String> = emptyMap(), shared: Boolean = false, styles: Boolean = false,
        sheetTarget: String = "worksheets/sheet1.xml"): java.io.File {
        val file = temporary.newFile("book-${System.nanoTime()}.xlsx")
        val parts = linkedMapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Inventario" sheetId="1" r:id="r1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="$sheetTarget"/>${if (shared) """<Relationship Id="sst" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>""" else ""}${if (styles) """<Relationship Id="sty" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" else ""}</Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>"""
        )
        parts.putAll(extras)
        ZipOutputStream(file.outputStream()).use { zip -> parts.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() } }
        return file
    }
    private fun read(rows: String) = XlsxInventoryReader().read(workbook(headers + rows))

    @Test fun associatesSerialsWithoutAddingProductAndGrandTotals() {
        val report = read(heading() + serial(3, "000001") + serial(4, "000002") + row(5, text("A5", "Total"), number("D5", "2")))
        assertEquals(1, report.products.size)
        assertEquals("0001", report.products.single().code)
        assertEquals(2, report.serialCount)
        assertEquals(2L, report.products.single().serials.sumOf { requireNotNull(it.quantity) })
        assertEquals(2, report.summaryRows)
        assertEquals(0, report.errorCount)
        assertEquals(1, report.structure!!.standaloneSummaryRows)
        assertEquals(1, report.structure.productHeaderRows)
        assertEquals(2, report.structure.serialRows)
        assertEquals(5, report.structure.nonEmptyRows)
        assertTrue(report.structure.xlsxValidated)
        assertEquals(com.example.app_clisa.data.excel.ReportCompleteness.UNKNOWN,
            com.example.app_clisa.data.excel.ExcelCompletenessValidator.assess(report, "ab".repeat(32)).state)
    }
    @Test fun productColumnHeaderAndRepeatedProductLabelsAreSupported() {
        val report = read(row(2, text("C2", "[0001] Parlante"), number("D2", "1")) +
            row(3, text("A3", "SER-01"), text("C3", "[0001] Parlante"), number("D3", "1")))
        assertEquals(1, report.serialCount); assertEquals(0, report.errorCount)
    }
    @Test fun duplicateProductsAndSerialsAreReportedAndNotCountedAgain() {
        val report = read(heading(quantity = "1") + serial(3, "SER-01") + heading(4, quantity = "1") + serial(5, "SER-01"))
        assertEquals(2, report.products.size); assertEquals(0, report.serialCount)
        assertEquals(2, report.serialRowCount)
        assertEquals(2, report.errorCount)
        assertTrue(report.issues.all { "duplicado" in it.message })
    }
    @Test fun rejectsNegativeFractionalAndMissingQuantities() {
        val report = read(heading(quantity = "0") + serial(3, "S1", "-1") + serial(4, "S2", "1.5") + row(5, text("A5", "S3")))
        assertEquals(0, report.serialCount)
        assertEquals(3, report.errorCount)
    }
    @Test fun orphanAndMismatchedReferencesDoNotAttachToPreviousProduct() {
        val report = read(serial(2, "ORPHAN") + heading(3, quantity = "0") + serial(4, "S2", reference = "9999"))
        assertEquals(0, report.serialCount); assertEquals(2, report.errorCount)
    }
    @Test fun summaryMismatchIsVisibleAndTotalsStaySeparate() {
        val report = read(heading(quantity = "5") + serial(3, "S1"))
        assertEquals(1, report.errorCount); assertTrue(report.issues.single().message.contains("no coincide"))
        assertEquals(1L, report.products.single().serials.single().quantity)
    }
    @Test fun recognizesReorderedHeadersAndIgnoresTitleRows() {
        val custom = row(1, text("A1", "Reporte CLISA")) + row(2, text("C2", "Cantidad"), text("D2", "Producto"), text("E2", "Lote/Nº de serie"), text("F2", "Referencia Interna")) +
            row(3, text("D3", "[0001] Parlante"), number("C3", "1")) + row(4, text("E4", "001"), number("C4", "1"))
        val report = XlsxInventoryReader().read(workbook(custom))
        assertEquals(0, report.errorCount); assertEquals("001", report.products.single().serials.single().code)
    }
    @Test fun preservesNumericCodesWithZeroMasksAndReadsSharedRichText() {
        val rows = headers + row(2, """<c r="A2" t="s"><v>0</v></c>""", number("D2", "1")) +
            row(3, number("A3", "12", 1), number("B3", "1", 2), number("D3", "1.0"))
        val report = XlsxInventoryReader().read(workbook(rows, mapOf(
            "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><r><t>[0001] </t></r><r><t>Parlante</t></r></si></sst>""",
            "xl/styles.xml" to """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts><numFmt numFmtId="164" formatCode="000000"/><numFmt numFmtId="165" formatCode="0000"/></numFmts><cellXfs><xf numFmtId="0"/><xf numFmtId="164"/><xf numFmtId="165"/></cellXfs></styleSheet>"""
        ), shared = true, styles = true, sheetTarget = "/xl/worksheets/sheet1.xml"))
        assertEquals(0, report.errorCount); assertEquals("000012", report.products.single().serials.single().code)
    }
    @Test fun formulasAreReportedWithoutUsingTheirCachedResults() {
        val report = read(heading(quantity = "0") + row(3, text("A3", "S1"), """<c r="D3"><f>1+1</f><v>2</v></c>"""))
        assertEquals(0, report.serialCount); assertTrue(report.issues.any { "fórmula" in it.message })
    }
    @Test fun missingRequiredColumnsIsReported() {
        val report = XlsxInventoryReader().read(workbook(row(1, text("A1", "Producto"))))
        assertEquals(1, report.errorCount); assertEquals(0, report.products.size)
    }
    @Test fun invalidSerialDoesNotLoseAssociationForFollowingValidSerials() {
        val report = read(heading(quantity = "1") +
            row(3, text("A3", "BAD"), """<c r="D3"><f>1+1</f><v>2</v></c>""") + serial(4, "GOOD"))
        assertEquals(1, report.errorCount)
        assertEquals("GOOD", report.products.single().serials.single().code)
    }
    @Test fun detectsDuplicateIdentifiersEvenWhenFirstQuantityIsInvalid() {
        val report = read(heading(quantity = "0") + serial(3, "S1", "-1") + serial(4, "S1"))
        assertEquals(2, report.errorCount); assertEquals(0, report.serialCount)
        assertTrue(report.issues.any { "duplicado" in it.message })
    }
    @Test fun refusesNumericIdentifiersWithLostPrecision() {
        val report = read(heading(quantity = "0") + row(3, number("A3", "1234567890123456"), number("D3", "1")))
        assertEquals(0, report.serialCount); assertTrue(report.issues.any { "15 dígitos" in it.message })
    }
    @Test fun bracketedSkuMayContainItsOwnBrackets() {
        val report = read(row(2, text("A2", "[4408C001[AA]] Tinta (1)"), number("D2", "1")) +
            row(3, text("A3", "0000001"), text("C3", "[4408C001[AA]] Tinta"), number("D3", "1")))
        assertEquals("4408C001[AA]", report.products.single().code)
        assertEquals("Tinta", report.products.single().name)
        assertEquals(0, report.errorCount)
    }
    @Test fun uncodedGroupsAndTheirSeriesArePreservedWithoutInventingSkus() {
        val report = read(row(2, text("A2", "Parlante sin referencia (1)"), number("D2", "1")) +
            row(3, text("A3", "00001"), text("C3", "Parlante sin referencia"), number("D3", "1")))
        assertNull(report.products.single().code)
        assertEquals(1, report.uncodedProductCount)
        assertEquals("00001", report.products.single().serials.single().code)
        assertEquals(1, report.errorCount)
    }
    @Test fun sameSkuWithDifferentNamesNeverMergesGroupsOrReplacesAssociations() {
        val report = read(row(2, text("A2", "[SKU] Mouse (1)"), number("D2", "1")) +
            row(3, text("A3", "M001"), text("C3", "[SKU] Mouse"), number("D3", "1")) +
            row(4, text("A4", "[SKU] Teclado (1)"), number("D4", "1")) +
            row(5, text("A5", "T001"), text("C5", "[SKU] Teclado"), number("D5", "1")))
        assertEquals(2, report.products.size)
        assertEquals("M001", report.products[0].serials.single().code)
        assertEquals("T001", report.products[1].serials.single().code)
        assertEquals(1, report.uniqueProductCodeCount)
        assertEquals(1, report.errorCount)
    }
    @Test fun parenthesizedGroupCountIsRowsNotStock() {
        val report = read(row(2, text("A2", "[00780] Rodillo (2)"), number("D2", "1")) +
            row(3, text("A3", "001"), text("C3", "[00780] Rodillo"), number("D3", "0")) +
            row(4, text("A4", "002"), text("C4", "[00780] Rodillo"), number("D4", "1")))
        assertEquals(2, report.products.single().reportedSerialCount)
        assertEquals(1L, report.products.single().reportedQuantity)
        assertEquals(0, report.errorCount)
    }
    @Test fun spacesRemainInLiteralSeriesAndAllDuplicateOccurrencesAreFlagged() {
        val report = read(heading(quantity = "2") + serial(3, "0001 ") + serial(4, "0001"))
        assertEquals("0001 ", report.products.single().serials[0].code)
        assertEquals(2, report.serialRowCount); assertEquals(1, report.uniqueSerialCount)
        assertTrue(report.products.single().serials.none { it.valid })
        assertEquals(2, report.errorCount)
    }
    @Test fun readsCompleteRealClisaExportWhenProvidedLocally() {
        val path = System.getenv("CLISA_EXCEL_SAMPLE")
        org.junit.Assume.assumeTrue("Set CLISA_EXCEL_SAMPLE to run the real-file regression.", !path.isNullOrBlank())
        val report = XlsxInventoryReader().read(java.io.File(requireNotNull(path)))
        assertEquals(1814, report.products.size)
        assertEquals(1453, report.uniqueProductCodeCount)
        assertEquals(346, report.uncodedProductCount)
        assertEquals(22762, report.serialRowCount)
        assertEquals(22714, report.uniqueSerialCount)
        assertEquals(567, report.errorCount)
        assertEquals(4068L, report.products.flatMap { it.serials }.sumOf { requireNotNull(it.quantity) })
        assertEquals(4068L, report.products.sumOf { requireNotNull(it.reportedQuantity) })
        assertEquals(2, report.products.flatMap { it.serials }.count { requireNotNull(it.quantity) < 0 })
        assertEquals(86, report.products.flatMap { it.serials }.count { it.code.startsWith("0") })
        assertTrue(report.products.all { it.reportedSerialCount == it.serials.size })
        assertTrue(report.products.any { it.code == "4408C001[AA]" })
        val rodillo = report.products.single { it.code == "00780" }
        assertEquals(20, rodillo.serials.size)
        assertEquals(19L, rodillo.reportedQuantity)
        println("CLISA real: groups=${report.products.size}, uniqueSkus=${report.uniqueProductCodeCount}, uncoded=${report.uncodedProductCount}, serialRows=${report.serialRowCount}, uniqueSeries=${report.uniqueSerialCount}, rowValidSeries=${report.serialCount}, issues=${report.errorCount}")
    }
    @Test(expected = org.xml.sax.SAXException::class)
    fun refusesDoctypeDeclarations() {
        val file = workbook("", mapOf("xl/workbook.xml" to """<!DOCTYPE workbook [<!ENTITY x "unsafe">]><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">&x;</workbook>"""))
        XlsxInventoryReader().read(file)
    }
    @Test(expected = IllegalArgumentException::class)
    fun refusesNonExcelFiles() { XlsxInventoryReader().read(temporary.newFile().also { it.writeText("not an xlsx") }) }
}
