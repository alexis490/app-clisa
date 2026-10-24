package com.example.app_clisa

import com.example.app_clisa.data.DemoInventory
import org.junit.Assert.*
import org.junit.Test

class InventoryTest {
    private val product = DemoInventory.products.first()
    private val receiver = DemoInventory.receivers.first()
    private fun validate(codes: List<String>, quantity: Int = codes.size, used: Set<String> = emptySet()) =
        DemoInventory.validate(product, codes, quantity, receiver, "Pagado", used)

    @Test fun acceptsAvailableUnits() { assertNull(validate(listOf("LEN001-A", "LEN001-B"))) }
    @Test fun rejectsPreviouslyWithdrawnUnit() { assertNotNull(validate(listOf("LEN001-A"), used = setOf("LEN001-A"))) }
    @Test fun rejectsDuplicatesAndOtherProducts() {
        assertNotNull(validate(listOf("LEN001-A", "LEN001-A")))
        assertNotNull(validate(listOf("LOG435-A")))
    }
    @Test fun rejectsQuantityMismatchAndEmptyExit() {
        assertNotNull(validate(listOf("LEN001-A"), quantity = 2))
        assertNotNull(validate(emptyList()))
    }
    @Test fun requiresReceiverAndPayment() {
        assertNotNull(DemoInventory.validate(product, listOf("LEN001-A"), 1, null, "Pagado", emptySet()))
        assertNotNull(DemoInventory.validate(product, listOf("LEN001-A"), 1, receiver, "", emptySet()))
    }
    @Test fun findsProductByUnitCode() { assertEquals(product, DemoInventory.find(" len001-a ")) }
}
