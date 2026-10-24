package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class InventorySafety4a1Test {
    private val hash = "ab".repeat(32)
    private fun report(): ExcelInventory {
        val s = ExcelSerial("0001", 1, "Sheet", 3)
        return ExcelInventory(listOf(ExcelProduct("SKU/1", "Producto", 1, listOf(s), "Sheet", 2, 1)), emptyList(), 0, 1,
            ExcelStructure(true, 1, 1, 3, 1, 1, 1, 0, 0, 0))
    }
    private fun snapshot() = InventoryReadSnapshot(listOf(ComparedProduct("SKU_1", "SKU/1", "Producto", 1, 7, "v1")),
        listOf(ComparedExistence("legacy-real", "SKU_1", "SKU/1", "0001", 1, "DISPONIBLE", "v1")), 10, 20)
    private fun assess(r: ExcelInventory = report()) = ExcelCompletenessValidator.assess(r, hash)

    @Test fun validFormatDoesNotProveCompleteInventory() {
        val a = assess(); assertTrue(a.technicalValid); assertEquals(ReportCompleteness.UNKNOWN, a.state)
        assertTrue("TRUSTED_EXPORT_EVIDENCE_MISSING" in a.reasons)
    }
    @Test fun missingTrackingCannotCertifyCompleteness() {
        val a = assess(report().copy(structure = null)); assertEquals(ReportCompleteness.UNKNOWN, a.state)
    }
    @Test fun missingColumnsAndUnparsedRowsAreIncomplete() {
        val r = report()
        for (s in listOf(r.structure!!.copy(sheetsWithHeaders = 0), r.structure.copy(uninterpretedRows = 1, nonEmptyRows = 4)))
            assertEquals(ReportCompleteness.INCOMPLETE, assess(r.copy(structure = s)).state)
    }
    @Test fun unclassifiedPreambleRemainsUnknown() {
        val r = report(); val a = assess(r.copy(structure = r.structure!!.copy(nonEmptyRows = 4, preambleRows = 1)))
        assertEquals(ReportCompleteness.UNKNOWN, a.state); assertTrue("UNCLASSIFIED_PREAMBLE" in a.reasons)
    }
    @Test fun duplicateSeriesAndProductHeadersBlockedWithoutChoosingFirst() {
        val r = report(); val p = r.products.single()
        val duplicate = p.copy(serials = p.serials + p.serials.single().copy(row = 4), reportedQuantity = 2, reportedSerialCount = 2)
        assertTrue("DUPLICATE_OR_EMPTY_SERIAL" in assess(r.copy(products = listOf(duplicate))).reasons)
        assertTrue("DUPLICATE_PRODUCT_CODE" in assess(r.copy(products = listOf(p, p.copy(row = 5)))).reasons)
    }
    @Test fun summaryIsComparedToDetailAndNeverAddedTwice() {
        val r = report(); val p = r.products.single()
        assertTrue(assess().technicalValid)
        assertTrue("SUMMARY_DETAIL_MISMATCH" in assess(r.copy(products = listOf(p.copy(reportedQuantity = 2)))).reasons)
        assertTrue("SUMMARY_DETAIL_MISMATCH" in assess(r.copy(products = listOf(p.copy(reportedSerialCount = 2)))).reasons)
    }
    @Test fun invalidQuantityOverflowAndGroupMembershipBlocked() {
        val r = report(); val p = r.products.single()
        for (n in listOf<Long?>(null, -1)) {
            val row = p.serials.single().copy(quantity = n)
            assertEquals(ReportCompleteness.INCOMPLETE, assess(r.copy(products = listOf(p.copy(serials = listOf(row))))).state)
        }
        val overflow = listOf(p.serials.single().copy(quantity = Long.MAX_VALUE), p.serials.single().copy(code = "0002", row = 4))
        assertEquals(ReportCompleteness.INCOMPLETE, assess(r.copy(products = listOf(p.copy(serials = overflow)))).state)
        assertTrue("INVALID_GROUP_MEMBERSHIP" in assess(r.copy(products = listOf(p.copy(serials = listOf(p.serials.single().copy(row = 1)))))).reasons)
    }
    private fun proof(): Pair<ExportManifest, ExportAuthorityPolicy> {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val m = ExportManifest(hash, "CLISA", 100, 1, 1, 1, true, "test-authority-only", "")
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(m.signingBytes()); sign() }
        return m.copy(signatureBase64 = Base64.getEncoder().encodeToString(signature)) to
            ExportAuthorityPolicy("CLISA", 200, 500, mapOf("test-authority-only" to key.public))
    }
    @Test fun trustedSignedUnfilteredManifestCanVerifySyntheticReportOnly() {
        val (m, policy) = proof()
        val a = ExcelCompletenessValidator.assess(report(), hash, m, policy)
        assertEquals(ReportCompleteness.COMPLETE_VERIFIED, a.state); assertNotNull(a.manifestDigest)
    }
    @Test fun untrustedKeyWrongFileFilteredOrTamperedManifestCannotCertify() {
        val (m, policy) = proof()
        assertEquals(ReportCompleteness.UNKNOWN, ExcelCompletenessValidator.assess(report(), hash, m, policy.copy(trustedPublicKeys = emptyMap())).state)
        for (bad in listOf(m.copy(unfiltered = false), m.copy(fileSha256 = "ff".repeat(32)), m.copy(totalQuantity = 999), m.copy(scope = "OTRA")))
            assertEquals(ReportCompleteness.INCOMPLETE, ExcelCompletenessValidator.assess(report(), hash, bad, policy).state)
        assertEquals(ReportCompleteness.INCOMPLETE, ExcelCompletenessValidator.assess(report(), hash, m, policy.copy(nowMillis = 9999)).state)
    }
    @Test fun readerIssuesOverrideEvenTrustedProof() {
        val (m, policy) = proof()
        assertEquals(ReportCompleteness.INCOMPLETE, ExcelCompletenessValidator.assess(report().copy(errorCount = 1), hash, m, policy).state)
    }
    @Test fun skuAndSeriesKeepTheirActualLegacyDocumentIds() {
        val ids = InventoryIdPolicy(snapshot())
        assertEquals("SKU_1", ids.product("SKU/1").documentId)
        assertEquals("legacy-real", ids.existence("SKU/1", "0001").documentId)
        assertTrue(ids.existence("SKU/1", "0001").existing)
    }
    @Test fun exactHashIdentitiesSeparateSlashesUnderscoresDelimitersAndParents() {
        val codes = listOf("A/B", "A_B", "A:B", "A|B", "á", "a", "A")
        assertEquals(codes.size, codes.map { InventoryIdPolicy.newProductId(it) }.toSet().size)
        assertNotEquals(InventoryIdPolicy.newExistenceId("ab", "c"), InventoryIdPolicy.newExistenceId("a", "bc"))
        assertNotEquals(InventoryIdPolicy.newExistenceId("one", "0001"), InventoryIdPolicy.newExistenceId("two", "0001"))
    }
    @Test fun repeatedIdsStableAndRetryRecognizesExistingEquivalent() {
        val baseline = snapshot().copy(products = emptyList(), existences = emptyList())
        val first = InventoryIdPolicy(baseline).product("NEW/SKU")
        repeat(5) { assertEquals(first, InventoryIdPolicy(baseline).product("NEW/SKU")) }
        val s = baseline.copy(products = listOf(ComparedProduct(first.documentId!!,"NEW/SKU","Nuevo",0,0)))
        assertTrue(InventoryIdPolicy(s).product("NEW/SKU").existing)
        val serial = InventoryIdPolicy(s).existence("NEW/SKU","00001")
        val retry = s.copy(existences = listOf(ComparedExistence(serial.documentId!!,first.documentId,"NEW/SKU","00001",1,"DISPONIBLE")))
        assertTrue(InventoryIdPolicy(retry).existence("NEW/SKU","00001").existing)
        assertEquals(serial.documentId,InventoryIdPolicy(retry).existence("NEW/SKU","00001").documentId)
    }
    @Test fun occupiedGeneratedPathsAreBlockedNotOverwritten() {
        val s = snapshot()
        val pid = InventoryIdPolicy.newProductId("NEW")
        assertTrue("PRODUCT_ID_COLLISION" in InventoryIdPolicy(s.copy(products = s.products + s.products.single().copy(id = pid, code = "OTHER"))).product("NEW").blockers)
        val eid = InventoryIdPolicy.newExistenceId("SKU_1","NEW")
        assertTrue("EXISTENCE_ID_COLLISION" in InventoryIdPolicy(s.copy(existences = s.existences + s.existences.single().copy(id = eid, code = "OTHER"))).existence("SKU/1","NEW").blockers)
    }
    @Test fun duplicateSkuOrSerialNeverSelectsFirstAndCrossProductSeriesBlocked() {
        val s = snapshot()
        val duplicateProducts = s.copy(products = s.products + s.products.single().copy(id = "copy"))
        assertFalse(InventoryIdPolicy(duplicateProducts).product("SKU/1").valid)
        val duplicateEntries = s.copy(existences = s.existences + s.existences.single().copy(id = "copy"))
        val identity = InventoryIdPolicy(duplicateEntries).existence("SKU/1","0001")
        assertEquals(2,identity.references.size); assertFalse(identity.valid)
        assertTrue("SERIAL_ASSIGNED_TO_OTHER_PRODUCT" in InventoryIdPolicy(s).existence("NEW","0001").blockers)
        assertFalse(InventoryIdPolicy(s.copy(complete = false)).product("SKU/1").valid)
        assertFalse(InventoryIdPolicy(s.copy(products = s.products + s.products.single().copy(code = "OTHER"))).product("SKU/1").valid)
        assertFalse(InventoryIdPolicy(s.copy(existences = s.existences + s.existences.single().copy(code = "OTHER"))).existence("SKU/1","0001").valid)
    }
    @Test fun proposalsUseDeterministicParentIdsAndNeverUpdateLegacyCounter() {
        val r = report(); val empty = snapshot().copy(products = emptyList(), existences = emptyList())
        val plan = InventoryDryRunPlanner.generate(InventoryComparator.compare(r, empty), ReportReadEvidence.fromReadResult(r,"file.xlsx",hash))
        val parent = plan.operations.single { it.type == SyncOperationType.CREATE_PRODUCT }
        val serial = plan.operations.single { it.type == SyncOperationType.CREATE_EXISTENCE }
        assertEquals(parent.documentId, serial.expected["productoId"])
        assertFalse(serial.blockers.contains("MISSING_DOCUMENT_VERSION"))
        assertTrue(plan.operations.none { "cantidadRestante" in it.expected })
        assertFalse(plan.productionExecutable); assertFalse(plan.destructiveEligible)
    }
    @Test fun incompleteAndUnknownCannotAuthorizeDeletionEvenWithManualScope() {
        for (r in listOf(report(),report().copy(errorCount = 1))) {
            val baseline = snapshot().copy(existences = snapshot().existences + snapshot().existences.single().copy(id = "absent", code = "ABSENT"))
            val evidence = ReportReadEvidence.fromReadResult(r,"file.xlsx",hash).copy(declaredScope = "TODO")
            val plan = InventoryDryRunPlanner.generate(InventoryComparator.compare(r,baseline),evidence)
            assertTrue(plan.operations.filter { it.type == SyncOperationType.DELETE_EXISTENCE }.all { "FULL_REPORT_UNPROVEN" in it.blockers })
        }
    }
    @Test fun concurrentQuantityDeletionCreationAndTimeCannotPassPreflight() {
        val r = report(); val s = snapshot()
        val plan = InventoryDryRunPlanner.generate(InventoryComparator.compare(r,s),ReportReadEvidence.fromReadResult(r,"file.xlsx",hash))
        for (changed in listOf(s.copy(existences = emptyList()), s.copy(existences = s.existences.map { it.copy(quantity = 0, state = "SIN_STOCK") }),
            s.copy(existences = s.existences + s.existences.single().copy(id = "new", code = "NEW")), s.copy(products = s.products.map { it.copy(stock = 0) })))
            assertTrue("INVENTORY_CHANGED" in SyncPlanFreshness.validate(plan,changed,hash).blockers)
        assertTrue("PLAN_EXPIRED" in SyncPlanFreshness.reviewAge(plan,1000,100).blockers)
        assertTrue("CLOCK_INCONSISTENT" in SyncPlanFreshness.reviewAge(plan,0,100).blockers)
    }
    @Test fun blankRecoveryProofDoesNotMarkMutationCommitted() {
        val r = report(); val s = snapshot().copy(existences = snapshot().existences.map { it.copy(quantity = 2) })
        val plan = InventoryDryRunPlanner.generate(InventoryComparator.compare(r,s),ReportReadEvidence.fromReadResult(r,"file.xlsx",hash))
        val op = plan.operations.first { it.changesInventory }
        val recovery = SyncRecoveryReview.inspect(plan,listOf(SyncJournalEntry(plan.planId,hash,op.operationId,SyncJournalState.APPLIED," ")))
        assertTrue(recovery.committedOperationIds.isEmpty())
        assertTrue("MISSING_AFTER_IMAGE_PROOF" in recovery.blockers)
        assertFalse(recovery.productionExecutable)
    }
    @Test fun realExcelRetainsReaderResultsAndCannotCertifyCompleteExport() {
        val path = System.getenv("CLISA_EXCEL_SAMPLE")
        org.junit.Assume.assumeTrue("Optional local Excel fixture",path != null)
        val actualPath = requireNotNull(path)
        val file = File(actualPath)
        val result = XlsxInventoryReader().read(file)
        assertEquals(1814,result.products.size); assertEquals(22762,result.serialRowCount); assertEquals(567,result.errorCount)
        val a = ExcelCompletenessValidator.assess(result,file.inputStream().use { SyncPlanIdentity.fileSha256(it) })
        assertEquals(ReportCompleteness.INCOMPLETE,a.state)
        assertTrue(result.structure!!.xlsxValidated)
        assertEquals(22762,result.structure.serialRows)
    }
}

