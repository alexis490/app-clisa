package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Test

class InventoryComparatorTest {
    private fun serial(code: String = "001", quantity: Long? = 1, valid: Boolean = true, row: Int = 3) =
        ExcelSerial(code, quantity, "Inventario", row, valid)
    private fun product(code: String? = "SKU", vararg rows: ExcelSerial, total: Long? = rows.sumOf { it.quantity ?: 0 }, row: Int = 2) =
        ExcelProduct(code, "Parlante", total, rows.toList(), "Inventario", row, rows.size)
    private fun dbProduct(id: String = "id-real", code: String? = "SKU", stock: Long? = 1, remaining: Long? = stock) =
        ComparedProduct(id, code, "Parlante", stock, remaining, "v1")
    private fun entry(id: String = "entry-real", code: String? = "001", quantity: Long? = 1, parent: String? = "id-real", sku: String? = "SKU") =
        ComparedExistence(id, parent, sku, code, quantity, if (quantity == 0L) "SIN_STOCK" else "DISPONIBLE", "v1")
    private fun compare(products: List<ExcelProduct> = listOf(product("SKU", serial())),
        db: List<ComparedProduct> = listOf(dbProduct()), entries: List<ComparedExistence> = listOf(entry()), errors: Int = 0) =
        InventoryComparator.compare(ExcelInventory(products, emptyList(), errors, products.size), InventoryReadSnapshot(db, entries, 10, 20))

    @Test fun identicalInventoryCostsZeroWrites() {
        val r = compare()
        assertEquals(1, r.existingProducts.size); assertEquals(1, r.unchangedExistences.size)
        assertEquals(0, r.estimatedInventoryWrites); assertTrue(r.conflicts.isEmpty()); assertTrue(r.stockDifferences.isEmpty())
    }
    @Test fun commercialSkuNeverBecomesDocumentId() {
        val r = compare(listOf(product("SFYRS/1000G", serial())), listOf(dbProduct("SFYRS_1000G", "SFYRS/1000G")),
            listOf(entry(parent = "SFYRS_1000G", sku = "SFYRS/1000G")))
        assertEquals("SFYRS_1000G", r.existingProducts.single().firestore.id)
        assertEquals("entry-real", r.unchangedExistences.single().firestore.id)
        assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun equalTotalDoesNotHideReplacedSerial() {
        val r = compare(listOf(product("SKU", serial("NEW"))))
        assertEquals("NEW", r.newExistences.single().excel.code)
        assertEquals("entry-real", r.absentExistences.single().id)
        assertTrue(r.unchangedExistences.isEmpty())
        assertEquals(1, r.estimatedInventoryWrites) // New serial can be created; absent serial is retained without a write.
    }
    @Test fun changedQuantityAndStockCountEachDocumentOnce() {
        val r = compare(listOf(product("SKU", serial(quantity = 2))))
        assertEquals(1, r.differentQuantities.size); assertEquals(2, r.estimatedInventoryWrites)
        assertEquals(2L, r.stockDifferences.single().expectedExcel)
    }
    @Test fun newProductAndItsIndividualEntriesAreSeparateWrites() {
        val r = compare(listOf(product("NEW", serial("0007"), serial("0008", row = 4))), emptyList(), emptyList())
        assertEquals(1, r.newProducts.size); assertEquals(2, r.newExistences.size)
        assertNull(r.newExistences.first().productId); assertEquals(3, r.estimatedInventoryWrites)
    }
    @Test fun duplicateSkuInExcelDoesNotSelectOrMergeFirstGroup() {
        val r = compare(listOf(product("SKU", serial()), product("SKU", serial("002"), row = 5)))
        assertTrue(r.existingProducts.isEmpty()); assertTrue(r.newExistences.isEmpty())
        assertEquals(2, r.conflicts.first { it.kind == "SKU duplicado en Excel" }.references.size)
        assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun duplicateFirestoreSkuPreservesBothReferencesAndNeverPicksFirst() {
        val r = compare(db = listOf(dbProduct(), dbProduct("second")))
        assertTrue(r.existingProducts.isEmpty())
        assertEquals(2, r.conflicts.first { it.kind == "SKU duplicado en Firestore" }.references.size)
        assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun duplicateSerialInDatabaseEvenWithinSameProductIsConflict() {
        val r = compare(entries = listOf(entry(), entry("other")))
        assertTrue(r.unchangedExistences.isEmpty())
        assertEquals(2, r.conflicts.first { it.kind == "Serie vacía o duplicada en Firestore" }.references.size)
        assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun duplicateExcelSerialCannotBeCountedAsNewOrUnchanged() {
        val r = compare(listOf(product("SKU", serial(), serial(row = 4))))
        assertTrue(r.newExistences.isEmpty()); assertTrue(r.unchangedExistences.isEmpty())
        assertTrue(r.conflicts.any { it.kind == "Serie vacía o duplicada en Excel" })
    }
    @Test fun sameSerialAssociatedToAnotherProductIsConflictNotNewEntry() {
        val r = compare(db = listOf(dbProduct(), dbProduct("other-product", "OTHER")),
            entries = listOf(entry(parent = "other-product", sku = "OTHER")))
        assertTrue(r.newExistences.isEmpty()); assertTrue(r.unchangedExistences.isEmpty())
        assertTrue(r.conflicts.any { it.kind == "Serie asociada a otro producto" })
    }
    @Test fun duplicateSeriesAcrossProductsIsExplicitConflictOnBothSides() {
        val r = compare(listOf(product("SKU", serial()), product("OTHER", serial(row = 5))),
            listOf(dbProduct(), dbProduct("other-product", "OTHER")),
            listOf(entry(), entry("other-entry", parent = "other-product", sku = "OTHER")))
        assertTrue(r.conflicts.any { it.kind == "Serie asociada a diferentes productos en Excel" })
        assertTrue(r.conflicts.any { it.kind == "Serie asociada a diferentes productos en Firestore" })
        assertTrue(r.unchangedExistences.isEmpty()); assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun stockDifferencesOfAbsentProductsRemainVisibleWithoutAssumingExcelZero() {
        val r = compare(emptyList(), listOf(dbProduct(stock = 0, remaining = 1)), listOf(entry(quantity = 0)))
        val diff = r.stockDifferences.single()
        assertNull(diff.expectedExcel); assertEquals(0L, diff.stock); assertEquals(1L, diff.remaining)
        assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun brokenProductReferenceAndSkuMismatchAreBothRejected() {
        for (bad in listOf(entry(parent = "missing"), entry(sku = "WRONG"))) {
            val r = compare(entries = listOf(bad))
            assertTrue(r.conflicts.any { it.kind.startsWith("Existencia inconsistente") })
            assertTrue(r.unchangedExistences.isEmpty()); assertEquals(0, r.estimatedInventoryWrites)
        }
    }
    @Test fun whitespaceIsOnlyUsedToDetectConflictsNotSilentlyRepairSerial() {
        val r = compare(listOf(product("SKU", serial(" 001 "))))
        assertTrue(r.newExistences.isEmpty()); assertTrue(r.absentExistences.isEmpty())
        assertTrue(r.conflicts.any { it.kind == "Fila Excel no válida" })
        assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun negativeAndNullAndOverflowAreNeverExpectedStock() {
        for (rows in listOf(listOf(serial(quantity = -1)), listOf(serial(quantity = null)),
            listOf(serial(quantity = Long.MAX_VALUE), serial("002", 1, row = 4)))) {
            val r = compare(listOf(product("SKU", *rows.toTypedArray())))
            assertNull(r.stockDifferences.single().expectedExcel); assertEquals(0, r.estimatedInventoryWrites)
        }
    }
    @Test fun zeroQuantityStillPreservesSerialIdentity() {
        val r = compare(listOf(product("SKU", serial(quantity = 0))), listOf(dbProduct(stock = 0)), listOf(entry(quantity = 0)))
        assertEquals(1, r.unchangedExistences.size); assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun remainingIsComparedSeparatelyAndNeverEstimatedAsStockWrite() {
        val r = compare(db = listOf(dbProduct(stock = 1, remaining = 7)))
        val diff = r.stockDifferences.single()
        assertEquals(1L, diff.expectedExcel); assertEquals(1L, diff.stock); assertEquals(7L, diff.remaining)
        assertEquals(1L, diff.existenceTotal); assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun uncodedGroupAndIncompleteExcelCannotTriggerMassDeletion() {
        val r = compare(listOf(product(null, serial("new"))), errors = 1)
        assertEquals(1, r.absentProducts.size); assertEquals(1, r.absentExistences.size)
        assertTrue(r.sourceHasErrors); assertTrue(r.newProducts.isEmpty()); assertTrue(r.newExistences.isEmpty())
        assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test(expected = IllegalArgumentException::class) fun partialSnapshotCannotProduceAbsences() {
        InventoryComparator.compare(ExcelInventory(emptyList(), emptyList(), 0, 0), InventoryReadSnapshot(emptyList(), emptyList(), 0, 0, false))
    }
    @Test fun conflictingHeaderTotalDoesNotBecomeProposedStock() {
        val r = compare(listOf(product("SKU", serial(), total = 40)))
        assertTrue(r.conflicts.any { it.kind == "Stock Excel no conciliable" }); assertEquals(0, r.estimatedInventoryWrites)
    }
    @Test fun cancellationIsPropagated() {
        var cancelled = false
        try {
            InventoryComparator.compare(ExcelInventory(listOf(product("SKU", serial())), emptyList(), 0, 1),
                InventoryReadSnapshot(emptyList(), emptyList(), 0, 0)) { throw java.util.concurrent.CancellationException() }
        } catch (_: java.util.concurrent.CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
