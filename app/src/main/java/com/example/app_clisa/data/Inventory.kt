package com.example.app_clisa.data

data class Product(val code: String, val name: String, val units: List<String>)
data class Receiver(val name: String, val store: String)

object DemoInventory {
    const val destinationStoreName = "TOQEN"
    val products = listOf(
        Product("LEN001", "Laptop Lenovo IdeaPad", ('A'..'E').map { "LEN001-$it" }),
        Product("LOG185", "Mouse Logitech M185", (1..12).map { "LOG185-${it.toString().padStart(2, '0')}" }),
        Product("SAM24", "Monitor Samsung 24”", ('A'..'C').map { "SAM24-$it" }),
        Product("LOG435", "Audífonos Logitech G435", listOf("LOG435-A", "LOG435-B")),
        Product("EPS3250", "Impresora Epson L3250", listOf("EPS3250-A")),
        Product("KING1TB", "SSD Kingston 1TB", ('A'..'D').map { "KING1TB-$it" }),
    )
    val receivers = listOf(Receiver("Carlos Mendoza", destinationStoreName), Receiver("María Lopez", destinationStoreName), Receiver("Juan Pérez", destinationStoreName), Receiver("Ana Torres", destinationStoreName))
    fun find(code: String): Product? = code.trim().uppercase().let { normalized -> products.find { it.code == normalized || normalized in it.units } }
    fun validate(product: Product?, codes: List<String>, quantity: Int?, receiver: Receiver?, payment: String, used: Set<String>): String? = when {
        product == null -> "Selecciona un producto válido."
        quantity == null || quantity <= 0 -> "Ingresa una cantidad mayor que cero."
        codes.size != quantity -> "La cantidad debe coincidir con los códigos de las unidades."
        codes.distinct().size != codes.size -> "No puedes repetir códigos de unidad."
        codes.any { it !in product.units } -> "Hay códigos que no pertenecen al producto."
        codes.any { it in used } -> "Una de las unidades ya fue retirada. Revisa el stock disponible."
        receiver == null -> "Selecciona el usuario que retira."
        payment !in listOf("Pagado", "Pendiente") -> "Selecciona el estado de pago."
        else -> null
    }
}
