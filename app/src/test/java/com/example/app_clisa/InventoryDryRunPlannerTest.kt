package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Test

class InventoryDryRunPlannerTest {
    private val hash = "ab".repeat(32)
    private val evidence = ReportReadEvidence("stock.xlsx", hash, true, true, true, true, 0, 0, "CLISA completo (declaración sin prueba)")
    private fun serial(code: String = "001", quantity: Long = 1, row: Int = 3) = ExcelSerial(code, quantity, "Excel", row)
    private fun product(code: String = "SKU/1", rows: List<ExcelSerial> = listOf(serial()), row: Int = 2) =
        ExcelProduct(code, "Parlante", rows.sumOf { it.quantity ?: 0 }, rows, "Excel", row, rows.size)
    private fun dbProduct(id: String = "SKU_1", code: String = "SKU/1", stock: Long = 1, remaining: Long = stock, version: String? = "v1") =
        ComparedProduct(id, code, "Parlante", stock, remaining, version)
    private fun entry(id: String = "entry-real", code: String = "001", quantity: Long = 1, parent: String = "SKU_1", sku: String = "SKU/1", version: String? = "v1") =
        ComparedExistence(id, parent, sku, code, quantity, if (quantity == 0L) "SIN_STOCK" else "DISPONIBLE", version)
    private fun comparison(source: List<ExcelProduct> = listOf(product()), db: List<ComparedProduct> = listOf(dbProduct()),
        rows: List<ComparedExistence> = listOf(entry()), errors: Int = 0): InventoryComparison =
        InventoryComparator.compare(ExcelInventory(source, emptyList(), errors, source.size), InventoryReadSnapshot(db, rows, 10, 20))
    private fun plan(c: InventoryComparison = comparison(), e: ReportReadEvidence = evidence) = InventoryDryRunPlanner.generate(c, e)
    private fun SyncPlan.ops(type: SyncOperationType) = operations.filter { it.type == type }

    @Test fun newProductUsesVersionedProposedIdButCannotExecute() {
        val p = plan(comparison(db = emptyList(), rows = emptyList()))
        val op = p.ops(SyncOperationType.CREATE_PRODUCT).single()
        assertEquals("SKU/1", op.sku); assertEquals("productos", op.collection); assertEquals(InventoryIdPolicy.newProductId("SKU/1"), op.documentId); assertEquals("productos/${op.documentId}", op.path)
        assertEquals(1L, op.expected["stock"]); assertFalse("CLIENT_CREATION_REQUIRES_APPROVED_BACKEND" in op.blockers)
        assertFalse(p.productionExecutable)
    }
    @Test fun newSerialKeepsTheActualParentId() {
        val p = plan(comparison(source = listOf(product(rows = listOf(serial(), serial("002", row = 4))))))
        val op = p.ops(SyncOperationType.CREATE_EXISTENCE).single()
        assertEquals("SKU_1", op.productId); assertEquals("002", op.serial); assertEquals(InventoryIdPolicy.newExistenceId("SKU_1", "002"), op.documentId)
        assertEquals("SKU_1", op.expected["productoId"])
        assertTrue(p.ops(SyncOperationType.UPDATE_PRODUCT_STOCK).single().blockers.contains("ATOMIC_GROUP_BLOCKED"))
    }
    @Test fun changedQuantityCarriesBeforeAndExpectedValues() {
        val p = plan(comparison(source = listOf(product(rows = listOf(serial(quantity = 2))))))
        val op = p.ops(SyncOperationType.UPDATE_EXISTENCE).single()
        assertEquals("existencias/entry-real", op.path); assertEquals(1L, op.before["cantidad"]); assertEquals(2L, op.expected["cantidad"])
        assertEquals("DISPONIBLE", op.expected["estado"]); assertEquals("v1", op.observedVersion)
    }
    @Test fun absentExistenceIsAlwaysBlockedAndNeverTurnedIntoZero() {
        val p = plan(comparison(source = emptyList()))
        val op = p.ops(SyncOperationType.REVIEW_MISSING_EXISTENCE).single()
        assertEquals("existencias/entry-real", op.path); assertEquals(SyncValidation.REVIEW_REQUIRED, op.validation)
        assertTrue(op.expected.isEmpty()); assertTrue(op.blockers.isEmpty()); assertFalse(op.changesInventory)
        assertFalse(p.destructiveEligible)
    }
    @Test fun equalStockStillProducesADeleteAndCreateForDifferentSerials() {
        val p = plan(comparison(source = listOf(product(rows = listOf(serial("NEW"))))))
        assertEquals("001", p.ops(SyncOperationType.REVIEW_MISSING_EXISTENCE).single().serial)
        assertEquals("NEW", p.ops(SyncOperationType.CREATE_EXISTENCE).single().serial)
        assertTrue(p.ops(SyncOperationType.UPDATE_PRODUCT_STOCK).isEmpty())
    }
    @Test fun duplicatedSerialsNeverChooseFirstDocument() {
        val p = plan(comparison(rows = listOf(entry(), entry("duplicate"))))
        assertTrue(p.ops(SyncOperationType.BLOCK_CONFLICT).any { it.references.toSet() == setOf("existencias/entry-real", "existencias/duplicate") })
        assertTrue(p.operations.filter { it.changesInventory }.all { it.validation == SyncValidation.BLOCKED })
        assertTrue(p.ops(SyncOperationType.UPDATE_EXISTENCE).isEmpty())
    }
    @Test fun differentSkuAndIdAreNeverNormalizedIntoOneAnother() {
        val p = plan(comparison(source = listOf(product(rows = listOf(serial(quantity = 2))))))
        val op = p.ops(SyncOperationType.UPDATE_PRODUCT_STOCK).single()
        assertEquals("SKU/1", op.sku); assertEquals("SKU_1", op.documentId); assertEquals("productos/SKU_1", op.path)
    }
    @Test fun missingProductIsOnlyAReviewAndHistoryIsProtected() {
        val p = plan(comparison(source = emptyList()))
        assertEquals("productos/SKU_1", p.ops(SyncOperationType.REVIEW_MISSING_PRODUCT).single().path)
        assertTrue(p.operations.all { it.collection == null || it.collection in setOf("productos", "existencias") })
        assertTrue(p.operations.none { it.path?.startsWith("registros/") == true })
    }
    @Test fun completeDeclarationCannotAuthorizeDestruction() {
        val p = plan(comparison(source = emptyList()), evidence.copy(declaredScope = "EXPORTACION_COMPLETA_CERTIFICADA"))
        assertFalse(p.checks.single { it.code == "FULL_REPORT_PROVEN" }.satisfied)
        assertFalse(p.destructiveEligible); assertFalse(p.productionExecutable)
    }
    @Test fun unknownAndNonzeroUninterpretedRowsBothBlockChanges() {
        for (n in listOf(null, 1)) {
            val p = plan(comparison(source = listOf(product(rows = listOf(serial(quantity = 2))))), evidence.copy(uninterpretedRelevantRows = n))
            assertTrue(p.operations.filter { it.changesInventory }.all { "NO_UNINTERPRETED_ROWS" in it.blockers })
        }
    }
    @Test fun sourceErrorsBlockAllMutationsEvenIfSomeRowsWereRecognized() {
        val p = plan(comparison(source = listOf(product(rows = listOf(serial(quantity = 2)))), errors = 1))
        assertTrue(p.operations.filter { it.changesInventory }.all { "NO_CRITICAL_READ_ERRORS" in it.blockers })
    }
    @Test fun unresolvedConflictsBlockOtherwiseValidProducts() {
        val c = comparison(source = listOf(product(rows = listOf(serial(quantity = 2)))))
        val p = plan(c.copy(conflicts = listOf(ComparisonConflict("SKU duplicado", "OTHER", listOf("productos/other")))))
        assertTrue(p.operations.filter { it.changesInventory }.all { "NO_PENDING_CONFLICTS" in it.blockers })
    }
    @Test fun generatorIsDeterministicAcrossInputReadOrderAndRepeatedCalls() {
        val source = listOf(product(), product("OTHER", listOf(serial("999", row = 6)), 5))
        val db = listOf(dbProduct(), dbProduct("other-id", "OTHER"))
        val rows = listOf(entry(), entry("other-entry", "999", parent = "other-id", sku = "OTHER"))
        val p1 = plan(comparison(source, db, rows))
        val p2 = plan(comparison(source.reversed(), db.reversed(), rows.reversed()))
        assertEquals(p1, p2); assertEquals(p1.planId, p2.planId)
        assertEquals(p1.operations.size, p1.operations.map { it.operationId }.distinct().size)
    }
    @Test fun determinismAlsoPreservesAllDuplicateReferences() {
        val p1 = plan(comparison(rows = listOf(entry(), entry("copy"))))
        val p2 = plan(comparison(rows = listOf(entry("copy"), entry())))
        assertEquals(p1, p2)
    }
    @Test fun changedStockVersionOrNewSerialInvalidatesBaseline() {
        val c = comparison()
        val p = plan(c)
        val baseline = c.inventory!!
        assertTrue(SyncPlanFreshness.validate(p, baseline, hash).matchesObservedBaseline)
        for (changed in listOf(baseline.copy(products = listOf(dbProduct(stock = 0))),
            baseline.copy(products = listOf(dbProduct(version = "v2"))), baseline.copy(existences = baseline.existences + entry("new", "NEW")))) {
            assertFalse(SyncPlanFreshness.validate(p, changed, hash).matchesObservedBaseline)
        }
    }
    @Test fun changedFileAndIncompleteRevalidationCannotPass() {
        val c = comparison(); val p = plan(c)
        assertFalse(SyncPlanFreshness.validate(p, c.inventory!!, "ff".repeat(32)).matchesObservedBaseline)
        assertFalse(SyncPlanFreshness.validate(p, c.inventory!!.copy(complete = false), hash).matchesObservedBaseline)
    }
    @Test fun missingVersionsBlockPotentialUpdates() {
        val p = plan(comparison(source = listOf(product(rows = listOf(serial(quantity = 2)))),
            db = listOf(dbProduct(version = null)), rows = listOf(entry(version = null))))
        assertTrue(p.operations.filter { it.changesInventory && it.path != null }.all { "MISSING_DOCUMENT_VERSION" in it.blockers })
    }
    @Test fun unchangedDocumentsCostZeroWritesAndZeroAuditWrites() {
        val p = plan()
        assertEquals(2, p.ops(SyncOperationType.SKIP_UNCHANGED).size)
        assertEquals(0L, p.cost.potentialInventoryWrites); assertEquals(0L, p.cost.potentialAuditWrites)
        assertEquals(0L, p.cost.dryRunAdditionalReads); assertEquals(0L, p.cost.futureFullRevalidationReads)
    }
    @Test fun quotaCountsInventoryAuditAndDeletesSeparately() {
        val p = plan(comparison(source = listOf(product(rows = listOf(serial("NEW"))))))
        assertEquals(1L, p.cost.potentialInventoryWrites); assertEquals(0L, p.cost.potentialDeletes)
        assertEquals(4L, p.cost.potentialAuditWrites); assertEquals(5L, p.cost.potentialTotalWrites)
        assertFalse(p.cost.remainingDailyQuotaKnown)
        val op = p.ops(SyncOperationType.CREATE_EXISTENCE).single()
        val large = SyncPlanEstimator.estimate((1..20_001).map { op.copy(operationId = "$it", atomicGroup = "one-group") }, 24_561)
        assertTrue(large.exceedsFullDailyWriteQuota); assertEquals(20_001L, large.potentialInventoryWrites)
    }
    @Test fun legacyRemainingIsNeverWrittenInAMutation() {
        val p = plan(comparison(db = listOf(dbProduct(remaining = 9))))
        assertFalse(p.ops(SyncOperationType.BLOCK_CONFLICT).any { "LEGACY_COUNTER_POLICY" in it.blockers })
        assertTrue(p.operations.filter { it.changesInventory }.none { "cantidadRestante" in it.expected })
    }
    @Test fun missingParsingEvidenceNeverMeansSuccessfulValidation() {
        val p = InventoryDryRunPlanner.generate(comparison())
        assertNull(p.fileSha256); assertFalse(p.checks.single { it.code == "NO_UNINTERPRETED_ROWS" }.satisfied)
        assertFalse(p.productionExecutable)
    }
    @Test fun duplicateRequestsForOnePathAreBlockedRatherThanAppliedTwice() {
        val c = comparison(source = listOf(product(rows = listOf(serial(quantity = 2)))), db = listOf(dbProduct(remaining = 2)))
        val p = plan(c.copy(differentQuantities = c.differentQuantities + c.differentQuantities))
        assertTrue(p.ops(SyncOperationType.UPDATE_EXISTENCE).all { "MULTIPLE_OPERATIONS_SAME_PATH" in it.blockers })
        assertTrue(p.operations.filter { it.changesInventory }.all { "ATOMIC_GROUP_BLOCKED" in it.blockers })
    }
    @Test fun recoveryNeverRetriesAnAppliedOperationBlindly() {
        val p = plan(comparison(source = listOf(product(rows = listOf(serial(quantity = 2))))))
        val op = p.ops(SyncOperationType.UPDATE_EXISTENCE).single()
        val entry = SyncJournalEntry(p.planId, hash, op.operationId, SyncJournalState.APPLIED, "after-proof")
        val recovery = SyncRecoveryReview.inspect(p, listOf(entry))
        assertTrue(op.operationId in recovery.committedOperationIds); assertFalse(op.operationId in recovery.pendingOperationIds)
        assertTrue("PARTIAL_OR_COMPLETED_RUN_REQUIRES_RECONCILIATION" in recovery.blockers); assertFalse(recovery.productionExecutable)
        assertTrue("DUPLICATE_JOURNAL_OPERATION" in SyncRecoveryReview.inspect(p, listOf(entry, entry)).blockers)
    }
    @Test fun replayKeyDependsOnSourceAndPolicyNotOnTheChangingInventoryBaseline() {
        assertEquals(SyncRecoveryReview.sourceReplayKey(hash, "CLISA", "v1"), SyncRecoveryReview.sourceReplayKey(hash, "CLISA", "v1"))
        assertNotEquals(SyncRecoveryReview.sourceReplayKey(hash, "CLISA", "v1"), SyncRecoveryReview.sourceReplayKey(hash, "CLISA", "v2"))
    }
    @Test fun planExpiryAndClockRollbackRequireReview() {
        val p = plan()
        assertTrue(SyncPlanFreshness.reviewAge(p, 22, 100).withinConfiguredWindow)
        assertFalse(SyncPlanFreshness.reviewAge(p, 121, 100).withinConfiguredWindow)
        assertFalse(SyncPlanFreshness.reviewAge(p, 10, 100).withinConfiguredWindow)
        assertFalse(SyncPlanFreshness.reviewAge(p.copy(observedAtMillis = null), 22, 100).withinConfiguredWindow)
    }
    @Test fun malformedOrForeignJournalIsNotTreatedAsCommitted() {
        val p = plan(comparison(source = listOf(product(rows = listOf(serial(quantity = 2))))))
        val id = p.ops(SyncOperationType.UPDATE_EXISTENCE).single().operationId
        val foreign = SyncJournalEntry("other-plan", hash, id, SyncJournalState.APPLIED, "after")
        val review = SyncRecoveryReview.inspect(p, listOf(foreign))
        assertTrue(review.committedOperationIds.isEmpty()); assertTrue("JOURNAL_IDENTITY_MISMATCH" in review.blockers)
    }
    @Test fun realExcelProducesBlockedLocalProposalsWithoutReadingFirestore() {
        val path = System.getenv("CLISA_EXCEL_SAMPLE")
        org.junit.Assume.assumeTrue("Local real Excel sample is optional", path != null)
        val file = java.io.File(path!!)
        val excel = XlsxInventoryReader().read(file)
        val sha = file.inputStream().use { SyncPlanIdentity.fileSha256(it) }
        val readEvidence = ReportReadEvidence.fromReadResult(excel, file.name, sha)
        val c = InventoryComparator.compare(excel, InventoryReadSnapshot(emptyList(), emptyList(), 10, 20))
        val p = plan(c, readEvidence)
        assertEquals(1814, excel.products.size); assertEquals(22762, excel.serialRowCount); assertEquals(567, excel.errorCount)
        assertTrue(p.operations.filter { it.changesInventory }.all { it.validation == SyncValidation.BLOCKED })
        assertFalse(p.destructiveEligible); assertFalse(p.productionExecutable)
        assertEquals(0L, p.cost.dryRunAdditionalReads)
        System.getenv("CLISA_DRY_RUN_REPORT")?.let { output ->
            java.io.File(output).writeText(buildString {
                appendLine("PRUEBA LOCAL: Excel real frente a un inventario vacío FICTICIO, no Firestore de producción.")
                appendLine("SHA-256: $sha")
                appendLine("Grupos: ${excel.products.size}; filas de series: ${excel.serialRowCount}; incidencias del lector: ${excel.errorCount}")
                appendLine("Plan: ${p.planId}")
                p.cost.operationCounts.forEach { (type, count) -> appendLine("$type: $count") }
                appendLine("Cambios propuestos (incluye bloqueados): ${p.cost.potentialInventoryWrites}")
                appendLine("Auditoría hipotética mínima: ${p.cost.potentialAuditWrites}; escrituras totales hipotéticas: ${p.cost.potentialTotalWrites}")
                appendLine("Bajas autorizadas: 0; escrituras ejecutadas: 0; lecturas Firestore adicionales: 0")
                p.checks.forEach { appendLine("${it.code}: ${if (it.satisfied) "COMPROBADO LOCALMENTE" else "BLOQUEO/PENDIENTE"}") }
            })
        }
    }
}
