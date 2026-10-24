package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ExportTemporalReviewTest {
    private val declaration = ManualExportTime(LocalDate.of(2026,10,22), LocalTime.of(10,0))
    private val cutoff = declaration.epochMillis()!!
    private val now = cutoff + 20 * 60_000
    private fun movement(id: String = "m", time: Long? = cutoff + 10 * 60_000) =
        InventoryMovement(id,"SALIDA","real-id",1,time,"COMPLETADO")
    private fun history(vararg rows: InventoryMovement) = MovementReadSnapshot(cutoff,now,rows.toList(),false,true)
    private fun review(d: ManualExportTime = declaration, h: MovementReadSnapshot? = null) = ExportTemporalReviewer.review(d,h,now)

    @Test fun limaDateAndTimeAreExactAndNeverAutoFilled() {
        assertNull(ManualExportTime().epochMillis())
        assertNull(ManualExportTime(date = declaration.date).epochMillis())
        assertNull(ManualExportTime(time = declaration.time).epochMillis())
        assertEquals("2026-10-22T15:00:00Z",java.time.Instant.ofEpochMilli(cutoff).toString())
    }
    @Test fun validDeclaredTimeDoesNotCertifyCutoff() {
        val r=review(); assertEquals(ExportTimeState.DECLARED_UNVERIFIED,r.timeState)
        assertEquals(TemporalDecision.REQUIRES_REVIEW,r.decision); assertFalse(r.cutoffReconciled)
        assertTrue("HISTORY_NOT_REVIEWED" in r.blockers)
    }
    @Test fun futureDateAndFutureHourBlocked() {
        for (d in listOf(declaration.copy(date=declaration.date!!.plusDays(1)),declaration.copy(time=LocalTime.of(10,21)))) {
            val r=review(d); assertEquals(ExportTimeState.FUTURE,r.timeState)
            assertEquals(TemporalDecision.BLOCKED,r.decision); assertTrue("EXPORT_TIME_IN_FUTURE" in r.blockers)
        }
    }
    @Test fun missingDateOrTimeIsNotImportTime() {
        for (d in listOf(ManualExportTime(),declaration.copy(date=null),declaration.copy(time=null))) {
            assertEquals(ExportTimeState.UNKNOWN,review(d).timeState)
            assertTrue("EXPORT_TIME_UNKNOWN" in review(d).blockers)
        }
    }
    @Test fun doubtfulDateAndOldExcelRequireReviewNotImplicitAcceptance() {
        assertEquals(ExportTimeState.DOUBTFUL,review(declaration.copy(uncertain=true)).timeState)
        val r=review(declaration.copy(date=declaration.date!!.minusDays(2)))
        assertEquals(ExportTimeState.STALE,r.timeState); assertTrue("EXPORT_TOO_OLD" in r.blockers)
    }
    @Test fun movementAfterExportIsDetectedWithoutSubtractingIt() {
        val r=review(h=history(movement()))
        assertEquals(MovementPosition.AFTER,r.movements.single().position)
        assertTrue("POST_EXPORT_MOVEMENTS_REQUIRE_REVIEW" in r.blockers)
        assertTrue("MOVEMENT_STOCK_RELATION_UNKNOWN" in r.blockers)
    }
    @Test fun movementBeforeExportIsNotSubtractedEither() {
        val r=review(h=history(movement(time=cutoff-1)))
        assertEquals(MovementPosition.BEFORE,r.movements.single().position)
        assertFalse("POST_EXPORT_MOVEMENTS_REQUIRE_REVIEW" in r.blockers)
        assertFalse(r.cutoffReconciled)
    }
    @Test fun movementExactlyAtMinuteCutoffIsAmbiguous() {
        val r=review(h=history(movement(time=cutoff)))
        assertEquals(MovementPosition.AT_CUTOFF,r.movements.single().position)
        assertTrue("MOVEMENT_AT_DECLARED_CUTOFF" in r.blockers)
    }
    @Test fun missingMalformedOrUnprovenDatesDoNotDisappear() {
        val r=review(h=history(movement("missing",null),movement("negative",-1),movement("future",now+1),movement("native")))
        assertEquals(3,r.movements.count { it.position==MovementPosition.UNKNOWN })
        assertTrue("MOVEMENT_TIMESTAMP_UNRELIABLE" in r.blockers)
        assertEquals(4,r.movements.size)
    }
    @Test fun noResultsIsNotProofOfNoUndatedHistory() {
        val r=review(h=history());assertTrue(r.historyConsulted);assertTrue(r.movements.isEmpty())
        assertTrue("HISTORY_SCOPE_UNPROVEN" in r.blockers);assertFalse(r.cutoffReconciled)
    }
    @Test fun incompleteHistoryAndMismatchedWindowsStayBlocked() {
        val r=review(h=history().copy(hasMore=true,indexedRangeFinished=false))
        assertTrue("HISTORY_PAGE_LIMIT_REACHED" in r.blockers)
        val changed=review(h=history(movement()).copy(cutoffMillis=cutoff-1))
        assertFalse(changed.historyConsulted);assertTrue("HISTORY_WINDOW_MISMATCH" in changed.blockers)
    }
    @Test fun unknownTypeEntradaMalformedActorProductAndDuplicateRecordsFlagged() {
        val r=review(h=history(movement().copy(type="ENTRADA",quantity=-1),movement().copy(productId=null)))
        assertTrue("MOVEMENT_SCHEMA_REQUIRES_REVIEW" in r.blockers)
        assertTrue("DUPLICATE_MOVEMENT_ID" in r.blockers)
        for (type in listOf("SALIDA","AJUSTE_ENTRADA","AJUSTE_SALIDA")) {
            assertFalse("MOVEMENT_SCHEMA_REQUIRES_REVIEW" in review(h=history(movement().copy(type=type))).blockers)
        }
    }
    private fun excel() = ExcelInventory(listOf(ExcelProduct("SKU","Producto",5,
        listOf(ExcelSerial("0001",5,"Sheet",3)),"Sheet",2,1)),emptyList(),0,1,
        ExcelStructure(true,1,1,3,1,1,1,0,0,0))
    private fun plan(r: ExportTemporalReview): SyncPlan {
        val read=excel()
        val db=InventoryReadSnapshot(listOf(ComparedProduct("real-id","SKU","Producto",4,4,"v1")),
            listOf(ComparedExistence("entry","real-id","SKU","0001",4,"DISPONIBLE","v1")),1,now)
        return InventoryDryRunPlanner.generate(InventoryComparator.compare(read,db),
            ReportReadEvidence.fromReadResult(read,"sample.xlsx","ab".repeat(32),r))
    }
    @Test fun excelIsReferenceRegardlessOfHistoricalMovements() {
        val p=plan(review(h=history(movement())))
        val stock=p.operations.single { it.type==SyncOperationType.UPDATE_PRODUCT_STOCK }
        assertEquals(4L,stock.before["stock"]);assertEquals(5L,stock.expected["stock"])
        assertEquals(SyncValidation.CANDIDATE,stock.validation)
        assertTrue(stock.blockers.isEmpty())
        assertTrue(p.operations.filter { it.changesInventory }.none { "TEMPORAL_CUTOFF_RECONCILED" in it.blockers })
        assertFalse(p.productionExecutable)
    }
    @Test fun changingExportTimeOrObservedHistoryDoesNotChangeInventoryPolicy() {
        val a=plan(review());val b=plan(review(h=history(movement())))
        assertEquals(a.planId,b.planId)
        assertEquals(a.planId,plan(review(declaration.copy(time=LocalTime.of(9,59)))).planId)
        assertEquals(b.planId,plan(review(h=history(movement()))).planId)
    }
    @Test fun manualTimeAndHistoryCannotUnlockAbsentSerialDeletion() {
        val r=excel(); val snapshot=InventoryReadSnapshot(listOf(ComparedProduct("real-id","SKU","Producto",1,1,"v1")),
            listOf(ComparedExistence("old","real-id","SKU","ABSENT",1,"DISPONIBLE","v1")),1,now)
        val p=InventoryDryRunPlanner.generate(InventoryComparator.compare(r,snapshot),ReportReadEvidence.fromReadResult(r,"f.xlsx","ab".repeat(32),review(h=history())))
        val retained=p.operations.single { it.type==SyncOperationType.REVIEW_MISSING_EXISTENCE }
        assertFalse(retained.changesInventory); assertTrue(retained.expected.isEmpty())
        assertTrue(p.operations.none { it.type==SyncOperationType.DELETE_EXISTENCE })
        assertFalse(p.destructiveEligible)
    }
}
