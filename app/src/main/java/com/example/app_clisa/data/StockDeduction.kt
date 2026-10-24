package com.example.app_clisa.data

data class StockDeduction(val productStock: Long, val entryStock: Long, val entryState: String) {
    companion object {
        fun calculate(productStock: Long, entryStock: Long, quantity: Long): StockDeduction {
            require(quantity > 0) { "Ingresa una cantidad mayor que cero." }
            require(productStock >= quantity && entryStock >= quantity) { "El stock cambió o la cantidad supera la existencia disponible." }
            val remaining = entryStock - quantity
            return StockDeduction(productStock - quantity, remaining, if (remaining == 0L) "SIN_STOCK" else "DISPONIBLE")
        }
    }
}
