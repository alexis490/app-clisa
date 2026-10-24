package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferReconciliationTest {
    @get:Rule val folder = TemporaryFolder()
    private val sha = "ab".repeat(32)
    private val trustedFixture = ReconciliationProofVerifier { _, proof -> proof.exportReference == "fixture:trusted" }
    private fun movement(id: String = "m1", formal: String? = "PENDIENTE", qty: Long = 2, date: Long = 500) =
        TransferMovement(id, "real-id", "SKU/1", "real-entry", "0001", qty, "SALIDA", "COMPLETADO", "Pendiente", formal,
            date, if (formal == "COMPLETADO") 800 else null, if (formal == "COMPLETADO") "ADMIN" else null, "clisa", "toqen", "mv1")
    private fun input(moves: List<TransferMovement> = listOf(movement()), official: Long = 10, app: Long = 8): TransferReconciliationInput {
        val p = ComparedProduct("real-id", "SKU/1", "Parlante", app, 99, "p-v1")
        val e = ComparedExistence("real-entry", p.id, p.code, "0001", app, if (app == 0L) "SIN_STOCK" else "DISPONIBLE", "e-v1")
        val source = ExcelProduct(p.code, p.name!!, official, listOf(ExcelSerial("0001", official, "Excel", 3)), "Excel", 2, 1)
        val excel = ExcelInventory(listOf(source), emptyList(), 0, 1)
        val compared = InventoryComparator.compare(excel, InventoryReadSnapshot(listOf(p), listOf(e), 1, 2))
        val plan = InventoryDryRunPlanner.generate(compared, ReportReadEvidence.fromReadResult(excel, "test.xlsx", sha))
        return TransferReconciliationInput(sha, plan, p, listOf(source), listOf(e), ProductMovementSnapshot(p.id, moves, true, 3000), 1000, false, false)
    }
    private fun proof(i: TransferReconciliationInput, included: Boolean = false): TransferReconciliationProof =
        TransferReconciliationProof(i.fingerprint, i.movements!!.movements.map { m -> MovementReconciliationEvidence(m.id,
            if (included) ExportInclusion.INCLUIDO else ExportInclusion.NO_INCLUIDO, ReconciliationEvidenceKind.REFERENCIA_VERIFICADA,
            "fixture:ticket-${m.id}", "Comprobante contrastado en fixture", "ADMIN", 2500) }, "fixture:trusted", true)
    private fun verified(i: TransferReconciliationInput, included: Boolean = false) = TransferReconciler.evaluate(i, proof(i, included), trustedFixture)
    private fun blocked(r: TransferReconciliationResult) { assertNull(r.stockConciliado); assertTrue(r.blockers.isNotEmpty()); assertFalse(r.productionExecutable) }
    private fun rejects(block: () -> Unit) { try { block(); fail("Debe rechazar") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun pendingProvenNotReflectedSubtractsExactlyOnce() { val i = input(); val r = verified(i); assertEquals(8L, r.stockConciliado); assertEquals(ReconciliationStatus.SIN_CAMBIOS, r.status); assertEquals(99L, r.cantidadRestante) }
    @Test fun completedProvenReflectedIsNeverSubtractedAgain() { assertEquals(8L, verified(input(listOf(movement(formal = "COMPLETADO")), 8), true).stockConciliado) }
    @Test fun completedNotReflectedStillRequiresOneDeduction() { assertEquals(8L, verified(input(listOf(movement(formal = "COMPLETADO")))).stockConciliado) }
    @Test fun pendingAlreadyReflectedIsNotDeductedAgain() { assertEquals(8L, verified(input(official = 8), true).stockConciliado) }
    @Test fun historicalUnverifiedIsNotInterpretedAsPendingOrCompleted() { blocked(verified(input(listOf(movement(formal = null))))) }
    @Test fun multipleTransfersAreSummedOnlyPerExactSerial() { assertEquals(6L, verified(input(listOf(movement(), movement("m2")), app = 6)).stockConciliado) }
    @Test fun preAndPostExportMovementsNeedIndependentEvidence() { val i = input(listOf(movement(date = 500), movement("m2", date = 1500)), app = 6); blocked(TransferReconciler.evaluate(i)); assertEquals(6L, verified(i).stockConciliado) }
    @Test fun uncertainDateBlocksEvenWithFixtureAttestation() { blocked(verified(input().copy(uncertainExport = true))) }
    @Test fun manualDateDoesNotProveExportInclusion() { blocked(TransferReconciler.evaluate(input())) }
    @Test fun officialAndAppDifferenceIsVisibleButNotAnAutomaticAdjustment() { val r = TransferReconciler.evaluate(input()); assertEquals(2L, r.difference); blocked(r) }
    @Test fun equalTotalsDoNotHideAmbiguousMovements() { blocked(TransferReconciler.evaluate(input(official = 8))) }
    @Test fun duplicateSerialIsBlockedEvenIfTotalsMatch() { val i = input(); val p = i.source.single(); blocked(verified(i.copy(source = listOf(p.copy(serials = p.serials + p.serials))))) }
    @Test fun unresolvedSourceIssueCannotBeBypassedWithProof() { blocked(verified(input().copy(unresolvedSourceIssues = true))) }
    @Test fun manualEvidenceAndEmptyReferencesAreInsufficient() {
        val i = input(); val p = proof(i)
        blocked(TransferReconciler.evaluate(i, p))
        blocked(TransferReconciler.evaluate(i, p.copy(evidence = p.evidence.map { it.copy(kind = ReconciliationEvidenceKind.DECLARACION_ADMINISTRATIVA) }), trustedFixture))
        blocked(TransferReconciler.evaluate(i, p.copy(evidence = p.evidence.map { it.copy(reference = "") }), trustedFixture))
    }
    @Test fun alteredEvidenceIsRejectedByTrustedVerifier() { val i = input(); blocked(TransferReconciler.evaluate(i, proof(i).copy(exportReference = "tampered"), trustedFixture)) }
    @Test fun movementChangeInvalidatesOldProofAndLocalDecision() {
        val i = input(); val old = proof(i); val next = i.copy(movements = i.movements!!.copy(movements = listOf(movement().copy(version = "mv2"))))
        blocked(TransferReconciler.evaluate(next, old, trustedFixture)); assertNotEquals(i.fingerprint, next.fingerprint)
        val dir = folder.newFolder(); val store = ReconciliationReviewStore(dir, "ADMIN", i)
        val journal = store.save("m1", ExportInclusion.NO_INCLUIDO, "Revisión ticket", "ticket1", "ADMIN", "ADMIN", 0, 1)
        assertEquals(1, store.current(journal).size)
        val changedStore = ReconciliationReviewStore(dir, "ADMIN", next)
        assertTrue(changedStore.current(changedStore.load()).isEmpty()); assertEquals(1, changedStore.staleCount(journal))
    }
    @Test fun differentExcelInvalidatesProof() { val i = input(); blocked(TransferReconciler.evaluate(i.copy(fileSha256 = "cd".repeat(32)), proof(i), trustedFixture)) }
    @Test fun stalePlanInvalidatesProofAndJournal() { val i = input(); blocked(TransferReconciler.evaluate(i.copy(plan = i.plan.copy(planId = "other-plan")), proof(i), trustedFixture)) }
    @Test fun repeatedEvaluationIsIdempotentAndDoesNotMutateSource() { val i = input(); assertEquals(verified(i), verified(i)); assertEquals(10L, i.source.single().reportedQuantity); assertEquals(8L, i.product.stock) }
    @Test fun userCannotSaveAdministrativeDecision() { val i = input(); val s = ReconciliationReviewStore(folder.newFolder(), "USER", i); rejects { s.save("m1", ExportInclusion.INCLUIDO, "razón", "ticket", "USER", "USER", 0, 1) } }
    @Test fun historyPaymentAndFormalizationRemainUnchanged() { val i = input(); val before = i.movements!!.movements.toList(); verified(i); assertEquals(before, i.movements.movements); assertEquals("Pendiente", before.single().payment); assertEquals("PENDIENTE", before.single().formalization) }
    @Test fun rawPlanRestoringStockIsBlockedDespiteSafeReconciledProposal() { val i = input(); val r = verified(i); assertEquals(8L, r.stockConciliado); assertTrue(r.planOperationStates.values.all { it == ReconciliationStatus.BLOQUEADO }) }
    @Test fun otherMovementTypesAndNegativeTargetsAreBlocked() { blocked(verified(input(listOf(movement().copy(type = "AJUSTE_ENTRADA"))))); blocked(verified(input(listOf(movement(qty = 20))))) }
    @Test fun paginatedHistoryIsNotCertifiedCompleteAtLimit() { val i = input(); blocked(verified(i.copy(movements = i.movements!!.copy(rangeFinished = false)))) }
    @Test fun durableDecisionsAreDeclarationsNotVerifiedFacts() {
        val i = input(); val dir = folder.newFolder(); val s = ReconciliationReviewStore(dir, "ADMIN", i)
        s.save("m1", ExportInclusion.INCLUIDO, "Documento revisado", "ticket123", "ADMIN", "SUPER_ADMIN", 0, 1)
        val reopened = ReconciliationReviewStore(dir, "ADMIN", i); val d = reopened.decisionFor("m1", reopened.load())!!
        assertEquals(IssueReviewState.EN_REVISION, d.state); assertEquals(sha, d.sourceSha256)
        blocked(TransferReconciler.evaluate(i))
    }
    @Test fun stalePlanAgeIsBlockedBeforeAnyProposal() {
        val i = input(); blocked(verified(i.copy(plan = i.plan.copy(observedAtMillis = 1), movements = i.movements!!.copy(readAtMillis = 1_000_000))))
    }
    @Test fun inconsistentAppCounterNeedsItsOwnVerifiedRepairReference() {
        val original = input(moves = emptyList(), official = 8, app = 8)
        val product = original.product.copy(stock = 2)
        val excel = ExcelInventory(original.source, emptyList(), 0, 1)
        val compared = InventoryComparator.compare(excel, InventoryReadSnapshot(listOf(product), original.entries, 1, 2))
        val plan = InventoryDryRunPlanner.generate(compared, ReportReadEvidence.fromReadResult(excel, "test.xlsx", sha))
        val i = original.copy(product = product, plan = plan)
        blocked(verified(i))
        val r = TransferReconciler.evaluate(i, proof(i).copy(counterRepairReference = "fixture:audited-counter-repair"), trustedFixture)
        assertEquals(8L, r.stockConciliado); assertEquals(ReconciliationStatus.CONCILIADO, r.status)
    }
    @Test fun persistedEvidenceRetainsSourcePlanQuantityDatesAndDocumentVersions() {
        val i = input(); val s = ReconciliationReviewStore(folder.newFolder(), "ADMIN", i)
        val journal = s.save("m1", ExportInclusion.NO_INCLUIDO, "Justificación", "Referencia verificable pendiente", "ADMIN", "ADMIN", 0, 2000)
        val original = journal.events.single().original
        listOf(i.plan.planId, i.fingerprint, i.fileSha256, "m1", "0001", "real-entry", "p-v1", "e-v1", "mv1", "PENDIENTE", "declaredExportMillis", "stockOficial").forEach { assertTrue("Falta $it", original.contains(it)) }
        assertEquals(2000L, journal.events.single().atMillis)
    }
    @Test fun oldPlanCannotBeReusedAgainstChangedProductOrEntryBaseline() {
        val i = input()
        blocked(verified(i.copy(product = i.product.copy(stock = 7))))
        blocked(verified(i.copy(entries = i.entries.map { it.copy(quantity = 7, version = "changed") })))
    }
}
