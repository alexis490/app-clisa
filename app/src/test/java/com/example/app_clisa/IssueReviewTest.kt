package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IssueReviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hash = "ab".repeat(32)
    private val emptyDb = InventoryReadSnapshot(emptyList(), emptyList(), 1, 2)
    private fun product(code: String? = null, serial: String = "SER ", qty: Long = -2, row: Int = 2) =
        ExcelProduct(code, "Parlante", qty, listOf(ExcelSerial(serial, qty, "Excel", row + 1)), "Excel", row, 1)
    private fun catalog(kind: ExcelIssueKind = ExcelIssueKind.MISSING_SKU, products: List<ExcelProduct> = listOf(product()),
        db: InventoryReadSnapshot? = emptyDb): IssueReviewCatalog {
        val p = products.first(); val s = p.serials.first()
        val issue = ExcelIssue("Excel", if (kind == ExcelIssueKind.MISSING_SKU || kind == ExcelIssueKind.DUPLICATE_SKU) p.row else s.row,
            "Revisar", kind, p.code, p.name, if (kind == ExcelIssueKind.SERIAL_WHITESPACE || kind == ExcelIssueKind.DUPLICATE_SERIAL || kind == ExcelIssueKind.NEGATIVE_QUANTITY) s.code else null)
        return IssueReviewCatalog(hash, ExcelInventory(products, listOf(issue), 1, products.size), db)
    }
    private fun decision(c: IssueReviewCatalog, proposed: String = "SKU-NUEVO", evidence: String = "Exportación verificada ticket 123",
        state: IssueReviewState = IssueReviewState.RESUELTO, reason: String = "Contrastado contra sistema principal") =
        c.items.single().let { IssueDecision(it.id, it.issue.kind, c.original(it), proposed, reason, evidence, "admin", 10, state, hash) }
    private fun validate(c: IssueReviewCatalog, d: IssueDecision) = c.validate(d, IssueReviewJournal(hash))
    private fun rejects(block: () -> Unit) { try { block(); fail("Debía rechazar") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun realFileLoadsAll567AndMatchesAdministrativeIds() {
        val path = System.getenv("CLISA_EXCEL_SAMPLE"); assumeTrue(!path.isNullOrBlank())
        val file = File(path!!); val sourceHash = file.inputStream().use { SyncPlanIdentity.fileSha256(it) }
        val c = IssueReviewCatalog(sourceHash, XlsxInventoryReader().read(file))
        assertEquals(567, c.items.size)
        assertEquals(mapOf(ExcelIssueKind.MISSING_SKU to 346, ExcelIssueKind.SERIAL_WHITESPACE to 156,
            ExcelIssueKind.DUPLICATE_SERIAL to 48, ExcelIssueKind.DUPLICATE_SKU to 15, ExcelIssueKind.NEGATIVE_QUANTITY to 2), c.items.groupingBy { it.issue.kind }.eachCount())
        assertEquals("3b1590ee8d8f58adc5e75f2c9b78b728dfe15038e134ff6f9c50d4b6e2b08fd2", c.items.first().id)
        assertEquals(567, c.counts(IssueReviewJournal(sourceHash)).pending)
    }
    @Test fun filtersUseKindStateAndMultipleProductWords() {
        val c = catalog(); val d = decision(c, state = IssueReviewState.BLOQUEADO)
        val journal = IssueReviewJournal(hash, listOf(d))
        assertEquals(1, c.filter(journal, ExcelIssueKind.MISSING_SKU, IssueReviewState.BLOQUEADO, " parlante ").size)
        assertTrue(c.filter(journal, state = IssueReviewState.RESUELTO).isEmpty())
        assertEquals(1, c.counts(journal).reviewed); assertEquals(0, c.counts(journal).resolved)
    }
    @Test fun explicitMissingSkuWithCompleteCollisionCheckIsAccepted() { val c = catalog(); validate(c, decision(c)); assertNull(c.excel.products.single().code) }
    @Test fun missingSkuCannotBeAutomaticallyInvented() { val c = catalog(); rejects { validate(c, decision(c, "")) } }
    @Test fun skuCollisionWithAnotherExcelGroupIsRejected() { val c = catalog(products = listOf(product(), product("SKU-NUEVO", row = 5))); rejects { validate(c, decision(c)) } }
    @Test fun skuCollisionWithFirestoreCommercialCodeIsRejected() {
        val c = catalog(db = emptyDb.copy(products = listOf(ComparedProduct("SKU_NUEVO", "SKU-NUEVO", "Otro", 1, 1))))
        rejects { validate(c, decision(c)) }
    }
    @Test fun incompleteBaselineCannotResolveIdentity() { val c = catalog(db = null); rejects { validate(c, decision(c)) }; validate(c, decision(c, state = IssueReviewState.BLOQUEADO)) }
    @Test fun normalizationIsOnlyAProposalAndOriginalIsPreserved() {
        val c = catalog(ExcelIssueKind.SERIAL_WHITESPACE, listOf(product("SKU")))
        assertEquals("SER ", c.original(c.items.single())); assertEquals("SER", c.suggestion(c.items.single()))
        validate(c, decision(c, "SER")); assertEquals("SER ", c.excel.products.single().serials.single().code)
    }
    @Test fun normalizedExcelSeriesCollisionIsRejected() {
        val c = catalog(ExcelIssueKind.SERIAL_WHITESPACE, listOf(product("A"), product("B", "SER", row = 6)))
        rejects { validate(c, decision(c, "SER")) }
    }
    @Test fun normalizedFirestoreSeriesCollisionIsRejected() {
        val c = catalog(ExcelIssueKind.SERIAL_WHITESPACE, listOf(product("A")), emptyDb.copy(existences = listOf(ComparedExistence("real-id", "B", "B", "SER", 1, "DISPONIBLE"))))
        rejects { validate(c, decision(c, "SER")) }
    }
    @Test fun duplicateSerialIncludesEveryProductAndFirstOccurrence() {
        val c = catalog(ExcelIssueKind.DUPLICATE_SERIAL, listOf(product("A", "SER"), product("B", "SER", row = 6)))
        assertEquals(listOf(3, 7), c.occurrences(c.items.single()).map { it.row })
        validate(c, decision(c, "CORREGIR_EN_ORIGEN")); rejects { validate(c, decision(c, "fusionar")) }
    }
    @Test fun duplicateSkuRetainsAllGroupsAndRealDocumentId() {
        val c = catalog(ExcelIssueKind.DUPLICATE_SKU, listOf(product("A/B"), product("A/B", row = 6)),
            emptyDb.copy(products = listOf(ComparedProduct("A_B", "A/B", "Parlante", 1, 2))))
        assertEquals(3, c.occurrences(c.items.single()).size)
        assertEquals("A_B", c.occurrences(c.items.single()).last().documentId)
        validate(c, decision(c, "REPETICION_LEGITIMA"))
    }
    @Test fun negativeQuantityRequiresExplicitDocumentedInteger() {
        val c = catalog(ExcelIssueKind.NEGATIVE_QUANTITY)
        assertEquals("-2", c.original(c.items.single())); validate(c, decision(c, "3"))
        listOf("", "-1", "1.5", "99999999999999999999999").forEach { rejects { validate(c, decision(c, it)) } }
        assertEquals(-2L, c.excel.products.single().serials.single().quantity)
    }
    @Test fun resolvedWithoutEvidenceOrReasonIsRejected() { val c = catalog(); rejects { validate(c, decision(c, evidence = " ")) }; rejects { validate(c, decision(c, reason = "")) } }
    @Test fun durableVersionedJournalSurvivesNewStoreAndKeepsHistory() {
        val dir = temporary.newFolder(); val c = catalog(); val d = decision(c)
        IssueReviewStore(dir, "admin").save(c, d, "admin", "ADMIN", 0)
        val reopened = IssueReviewStore(dir, "admin"); assertEquals(d, reopened.load(hash).events.single())
        reopened.save(c, d.copy(state = IssueReviewState.BLOQUEADO, atMillis = 20), "admin", "ADMIN", 1)
        assertEquals(2, reopened.load(hash).revision)
        rejects { reopened.save(c, d, "admin", "ADMIN", 0) }
    }
    @Test fun changedHashAndOtherAccountNeverReuseDecisions() {
        val dir = temporary.newFolder(); val c = catalog(); val store = IssueReviewStore(dir, "admin")
        store.save(c, decision(c), "admin", "ADMIN", 0)
        assertTrue(store.load("cd".repeat(32)).events.isEmpty())
        assertTrue(IssueReviewStore(dir, "other").load(hash).events.isEmpty())
        rejects { validate(c, decision(c).copy(sourceSha256 = "cd".repeat(32))) }
    }
    @Test fun corruptJournalFailsClosedInsteadOfOverwriting() {
        val dir = temporary.newFolder(); val c = catalog(); val store = IssueReviewStore(dir, "admin")
        store.save(c, decision(c), "admin", "ADMIN", 0)
        dir.listFiles()!!.single().appendText("corrupt")
        rejects { store.load(hash) }; rejects { store.save(c, decision(c), "admin", "ADMIN", 0) }
    }
    @Test fun userCannotWriteEvenThroughRepositoryEntryPoint() {
        val c = catalog(); val store = IssueReviewStore(temporary.newFolder(), "admin")
        rejects { store.save(c, decision(c), "admin", "USER", 0) }
        rejects { store.save(c, decision(c), "other", "SUPER_ADMIN", 0) }
        assertTrue(store.load(hash).events.isEmpty())
    }
    @Test fun adminCanSaveLocalProposal() { val c = catalog(); assertEquals(1, IssueReviewStore(temporary.newFolder(), "admin").save(c, decision(c), "admin", "ADMIN", 0).revision) }
    @Test fun superAdminCanSaveLocalProposal() { val c = catalog(); assertEquals(1, IssueReviewStore(temporary.newFolder(), "admin").save(c, decision(c), "admin", "SUPER_ADMIN", 0).revision) }
    @Test fun copyGenerationIsExplicitlyBlockedAndLeavesSourceBytesUntouched() {
        val file = temporary.newFile("original.xlsx"); file.writeBytes(byteArrayOf(1, 2, 3))
        val before = file.readBytes(); val c = catalog(); val prepared = c.prepareCopy(IssueReviewJournal(hash, listOf(decision(c))))
        assertEquals(1, prepared.approved.size); assertFalse(prepared.canGenerate); rejects { prepared.generate() }
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun allResolvedDoesNotChangeParserOrDryRunAndAppliedCountStaysZero() {
        val c = catalog(); val compared = InventoryComparator.compare(c.excel, emptyDb)
        val evidence = ReportReadEvidence.fromReadResult(c.excel, "original.xlsx", hash)
        val before = InventoryDryRunPlanner.generate(compared, evidence)
        val journal = IssueReviewJournal(hash, listOf(decision(c)))
        assertSame(before, c.reviewDryRun(before, journal).plan)
        assertFalse(c.reviewDryRun(before, journal).productionExecutable)
        assertEquals(1, c.counts(journal).resolved); assertEquals(0, c.counts(journal).appliedToCopy)
        assertEquals(before, InventoryDryRunPlanner.generate(compared, evidence)); assertFalse(before.productionExecutable)
        assertEquals(1, c.excel.errorCount)
    }
    @Test fun proposedSkuCannotBeSharedByTwoResolvedGroups() {
        val p1 = product(); val p2 = product(row = 8)
        val excel = ExcelInventory(listOf(p1, p2), listOf(p1, p2).map { ExcelIssue("Excel", it.row, "Falta", ExcelIssueKind.MISSING_SKU, productName = it.name) }, 2, 2)
        val c = IssueReviewCatalog(hash, excel, emptyDb)
        fun d(index: Int) = c.items[index].let { IssueDecision(it.id, it.issue.kind, c.original(it), "NUEVO", "Documento contrastado", "ticket123", "admin", 1, IssueReviewState.RESUELTO, hash) }
        c.validate(d(0), IssueReviewJournal(hash)); rejects { c.validate(d(1), IssueReviewJournal(hash, listOf(d(0)))) }
    }
    @Test fun externallyCorrectedCopyIsReparsedFromZeroAndHasIndependentJournal() {
        fun xlsx(serial: String): File {
            val file = temporary.newFile("source-${System.nanoTime()}.xlsx")
            val parts = mapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
                "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Excel" sheetId="1" r:id="r1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
                "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Lote/Nº de serie</t></is></c><c r="B1" t="inlineStr"><is><t>Referencia Interna</t></is></c><c r="C1" t="inlineStr"><is><t>Producto</t></is></c><c r="D1" t="inlineStr"><is><t>Cantidad</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>[0001] Parlante</t></is></c><c r="D2"><v>1</v></c></row><row r="3"><c r="A3" t="inlineStr"><is><t xml:space="preserve">$serial</t></is></c><c r="B3" t="inlineStr"><is><t>0001</t></is></c><c r="D3"><v>1</v></c></row></sheetData></worksheet>""")
            ZipOutputStream(file.outputStream()).use { zip -> parts.forEach { (name, xml) -> zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray()); zip.closeEntry() } }
            return file
        }
        val original = xlsx("SER "); val originalBytes = original.readBytes()
        val copy = xlsx("SER") // Represents a NEW export; this fixture helper is not a production writer.
        val oldHash = original.inputStream().use { SyncPlanIdentity.fileSha256(it) }
        val newHash = copy.inputStream().use { SyncPlanIdentity.fileSha256(it) }
        val reader = XlsxInventoryReader()
        assertEquals(1, reader.read(original).errorCount)
        val parsedCopy = reader.read(copy)
        assertEquals(0, parsedCopy.errorCount); assertEquals("0001", parsedCopy.products.single().code)
        assertNotEquals(oldHash, newHash)
        assertTrue(IssueReviewStore(temporary.newFolder(), "admin").load(newHash).events.isEmpty())
        assertTrue(IssueReviewCatalog(newHash, parsedCopy).items.isEmpty())
        assertArrayEquals(originalBytes, original.readBytes())
    }
}
