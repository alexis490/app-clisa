package com.example.app_clisa

import com.example.app_clisa.data.*
import org.junit.Assert.*
import org.junit.Test

class PendingExitTest {
    private val product = LiveProduct("p", "P001", "Producto", 10)
    private val entries = listOf(StockEntry("a", "p", "A", 2), StockEntry("b", "p", "B", 5))

    @Test fun distributesRequestedQuantityWithoutOverdrawingEntries() {
        val cart = addPendingExit(emptyList(), product, entries, 4)
        assertEquals(listOf(2L, 2L), cart.map { it.quantity })
        assertEquals(listOf("a", "b"), cart.map { it.entryId })
    }

    @Test fun repeatedProductAccumulatesWithStableRequestIds() {
        val first = addPendingExit(emptyList(), product, entries, 1)
        val second = addPendingExit(first, product, entries, 3)
        assertEquals(first.single().requestId, second.first().requestId)
        assertEquals(4L, second.sumOf { it.quantity })
        assertEquals(2, second.map { it.entryId }.distinct().size)
    }

    @Test fun keepsOtherProductsAndDoesNotMutatePreviousDraft() {
        val first = addPendingExit(emptyList(), product, entries, 2)
        val other = LiveProduct("q", "Q001", "Otro", 3)
        val next = addPendingExit(first, other, listOf(StockEntry("c", "q", "C", 3)), 1)
        assertEquals(2, next.size)
        assertEquals(first.single(), next.first())
        assertEquals(1, first.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsQuantityAboveRemainingProductStock() {
        val first = addPendingExit(emptyList(), product.copy(stock = 3), entries, 2)
        addPendingExit(first, product.copy(stock = 3), entries, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsQuantityAboveUnreservedExistences() {
        val first = addPendingExit(emptyList(), product, entries, 6)
        addPendingExit(first, product, entries, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroQuantity() { addPendingExit(emptyList(), product, entries, 0) }

    @Test(expected = IllegalArgumentException::class)
    fun neverAllocatesAnotherProductsExistence() {
        addPendingExit(emptyList(), product, listOf(StockEntry("x", "q", "X", 10)), 1)
    }

    @Test fun duplicatePageDocumentsCannotInflateAvailableStock() {
        val cart = addPendingExit(emptyList(), product, listOf(entries.first(), entries.first()), 2)
        assertEquals(1, cart.size)
        assertEquals(2L, cart.single().quantity)
    }
}
