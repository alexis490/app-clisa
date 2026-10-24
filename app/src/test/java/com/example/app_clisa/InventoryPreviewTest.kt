package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Test

class InventoryPreviewTest {
    private fun serial(code: String, qty: Long = 1, row: Int = 3) = ExcelSerial(code, qty, "Excel", row)
    private fun product(code: String = "SKU/001", serials: List<ExcelSerial> = listOf(serial("0007")), row: Int = 2) =
        ExcelProduct(code, "Parlante Creative", serials.sumOf { it.quantity ?: 0 }, serials, "Excel", row, serials.size)
    private fun db(id: String = "SKU_001", code: String = "SKU/001", stock: Long = 1, remaining: Long = stock) =
        ComparedProduct(id, code, "Parlante Creative", stock, remaining)
    private fun entry(id: String = "entry-7", code: String = "0007", qty: Long = 1, parent: String = "SKU_001", sku: String = "SKU/001") =
        ComparedExistence(id, parent, sku, code, qty, if (qty == 0L) "SIN_STOCK" else "DISPONIBLE")
    private fun build(products: List<ExcelProduct> = listOf(product()), db: List<ComparedProduct> = listOf(db()),
        entries: List<ComparedExistence> = listOf(entry())): Pair<InventoryComparison, InventoryPreview> {
        val excel = ExcelInventory(products, emptyList(), 0, products.size)
        val compared = InventoryComparator.compare(excel, InventoryReadSnapshot(db, entries, 1, 2))
        return compared to InventoryPreviewBuilder.build(compared, excel)
    }

    @Test fun everyGroupCountComesFromComparison() {
        val (c, p) = build()
        assertEquals(PreviewGroup.entries.toSet(), p.groups.keys)
        assertEquals(c.existingProducts.size, p.groups.getValue(PreviewGroup.EXISTING_PRODUCTS).size)
        assertEquals(c.unchangedExistences.size, p.groups.getValue(PreviewGroup.UNCHANGED).size)
        assertEquals(c.conflicts.size, p.groups.getValue(PreviewGroup.CONFLICTS).size)
        assertTrue(p.groups.getValue(PreviewGroup.NEW_PRODUCTS).isEmpty())
    }
    @Test fun detailsKeepSkuDocumentIdLeadingZerosAndBothQuantities() {
        val row = build(listOf(product(serials = listOf(serial("0007", 2))))).second.groups.getValue(PreviewGroup.DIFFERENT_QUANTITIES).single()
        assertEquals("SKU/001", row.productCode); assertEquals("SKU_001", row.productId)
        assertEquals("0007", row.serial); assertEquals("entry-7", row.existenceId)
        assertEquals(1L, row.oldQuantity); assertEquals(2L, row.excelQuantity)
    }
    @Test fun missingSerialHasNoInventedNewQuantityOrApprovedDeletion() {
        val row = build(listOf(product(serials = listOf(serial("OTHER"))))).second.groups.getValue(PreviewGroup.ABSENT_EXISTENCES).single()
        assertEquals("0007", row.serial); assertNull(row.excelQuantity)
        assertTrue(row.note.contains("se conserva", ignoreCase = true))
        assertTrue(row.note.contains("sin eliminación automática"))
    }
    @Test fun newProductsDoNotAcquireInventedIds() {
        val (_, p) = build(db = emptyList(), entries = emptyList())
        assertNull(p.products.single().documentId)
        assertNull(p.groups.getValue(PreviewGroup.NEW_EXISTENCES).single().productId)
        assertEquals(1L, p.products.single().expectedStock)
        assertEquals(1L, p.groups.getValue(PreviewGroup.NEW_PRODUCTS).single().expectedStock)
    }
    @Test fun stockAndLegacyCounterStaySeparate() {
        val row = build(db = listOf(db(stock = 1, remaining = 7))).second.groups.getValue(PreviewGroup.STOCK).single()
        assertEquals(1L, row.expectedStock); assertEquals(1L, row.stock); assertEquals(7L, row.remaining)
    }
    @Test fun ambiguousProductDocumentsRemainAvailableToConflictDetails() {
        val (c, p) = build(db = listOf(db(), db("another-id")))
        assertEquals(2, c.inventory!!.products.size)
        val row = p.groups.getValue(PreviewGroup.CONFLICTS).single { it.title == "SKU duplicado en Firestore" }
        assertEquals(setOf("SKU_001", "another-id"), row.facts.map { it.productId }.toSet())
        assertTrue(p.groups.getValue(PreviewGroup.EXISTING_PRODUCTS).isEmpty())
    }
    @Test fun duplicateSerialDetailsKeepAllDocumentIdsAndQuantities() {
        val (_, p) = build(entries = listOf(entry(), entry("entry-copy", qty = 0)))
        val row = p.groups.getValue(PreviewGroup.SERIAL_CONFLICTS).single()
        assertEquals(setOf("entry-7", "entry-copy"), row.facts.map { it.existenceId }.toSet())
        assertEquals(setOf(0L, 1L), row.facts.map { it.oldQuantity }.toSet())
    }
    @Test fun crossProductConflictResolvesPrefixedReferencesWithoutDiscardingIds() {
        val (_, p) = build(db = listOf(db(), db("other", "OTHER")),
            entries = listOf(entry(), entry("entry-other", parent = "other", sku = "OTHER")))
        val row = p.groups.getValue(PreviewGroup.SERIAL_CONFLICTS).single { it.title == "Serie asociada a diferentes productos en Firestore" }
        assertEquals(setOf("SKU_001", "other"), row.facts.map { it.productId }.toSet())
    }
    @Test fun localSearchMatchesWordsAnyOrderAndRealIdWithoutRepository() {
        val (_, p) = build()
        val row = p.groups.getValue(PreviewGroup.EXISTING_PRODUCTS).single()
        assertTrue(row.matches("  CREATIVE   parlante "))
        assertTrue(row.matches("SKU_001"))
        assertFalse(row.matches("producto inexistente"))
        assertTrue(p.groups.getValue(PreviewGroup.UNCHANGED).single().matches("0007"))
    }
    @Test fun sourceSummaryRowsNeverCreateIndividualExistences() {
        val excel = ExcelInventory(listOf(product()), emptyList(), 0, 50)
        val c = InventoryComparator.compare(excel, InventoryReadSnapshot(listOf(db()), listOf(entry()), 1, 2))
        val p = InventoryPreviewBuilder.build(c, excel)
        assertEquals(1, p.groups.getValue(PreviewGroup.UNCHANGED).size)
        assertEquals(1, p.products.single().details.count { it.serial != null })
    }
    @Test fun productDetailsExposeIndividualSerialsAndTheirQuantities() {
        val row = build(listOf(product(serials = listOf(serial("0007", 2))))).second.groups.getValue(PreviewGroup.EXISTING_PRODUCTS).single()
        val fact = row.facts.single()
        assertEquals("0007", fact.serial); assertEquals("entry-7", fact.existenceId)
        assertEquals(1L, fact.oldQuantity); assertEquals(2L, fact.excelQuantity)
    }
    @Test fun absentProductNeverBecomesExpectedZero() {
        val (_, p) = build(products = emptyList())
        val row = p.groups.getValue(PreviewGroup.ABSENT_PRODUCTS).single()
        assertNull(row.expectedStock); assertEquals(1L, row.stock)
        assertNull(p.products.single().expectedStock)
    }
    @Test fun invalidAndConflictingNewProductCannotAdvertiseReliableExpectedStock() {
        val p1 = product(serials = listOf(serial("0007", -1)))
        assertNull(build(listOf(p1), emptyList(), emptyList()).second.products.single().expectedStock)
        val (_, p) = build(listOf(product()), listOf(db("other", "OTHER")), listOf(entry(parent = "other", sku = "OTHER")))
        assertNull(p.products.single { it.group == PreviewGroup.NEW_PRODUCTS }.expectedStock)
    }
}
