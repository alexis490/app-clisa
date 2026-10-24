package com.example.app_clisa

import com.example.app_clisa.data.StockDeduction
import org.junit.Assert.assertEquals
import org.junit.Test

class StockDeductionTest {
    @Test fun partialWithdrawalKeepsExistenceAvailable() {
        val result = StockDeduction.calculate(12, 5, 3)
        assertEquals(9L, result.productStock)
        assertEquals(2L, result.entryStock)
        assertEquals("DISPONIBLE", result.entryState)
    }
    @Test fun lastUnitsExhaustOnlySelectedExistence() {
        val result = StockDeduction.calculate(10, 2, 2)
        assertEquals(8L, result.productStock)
        assertEquals(0L, result.entryStock)
        assertEquals("SIN_STOCK", result.entryState)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInsufficientProductStock() { StockDeduction.calculate(1, 5, 2) }
    @Test(expected = IllegalArgumentException::class) fun rejectsInsufficientExistenceStock() { StockDeduction.calculate(5, 1, 2) }
    @Test(expected = IllegalArgumentException::class) fun rejectsZero() { StockDeduction.calculate(5, 5, 0) }
    @Test(expected = IllegalArgumentException::class) fun rejectsNegative() { StockDeduction.calculate(5, 5, -1) }
}
