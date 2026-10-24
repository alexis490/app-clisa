package com.example.app_clisa.data

import com.google.firebase.firestore.*
import kotlinx.coroutines.CancellationException

data class CatalogProduct(val id: String, val code: String, val name: String, val stock: Long?,
    val category: String?, val brand: String?, val description: String?,
    val categoryId: String? = null, val brandId: String? = null)
data class CatalogDetails(val product: CatalogProduct, val error: String?)
data class CatalogPage(val products: List<CatalogProduct>, val cursor: DocumentSnapshot?, val hasMore: Boolean)
data class CatalogSearchPage(val products: List<CatalogProduct>, val cursor: DocumentSnapshot?, val hasMore: Boolean)
data class CatalogCodePage(val codes: List<String>, val cursor: DocumentSnapshot?, val hasMore: Boolean)
data class CatalogCategory(val id: String, val name: String, val description: String?)
data class CategoryPage(val categories: List<CatalogCategory>, val cursor: DocumentSnapshot?, val hasMore: Boolean)
enum class CatalogStockFilter { ALL, WITH_STOCK, WITHOUT_STOCK }
data class CategoryCounts(val all: Long, val withStock: Long, val withoutStock: Long)

/** Read-only catalog. No collection listeners, writes, or invented schema fields. */
class ProductCatalog {
    private val db = FirebaseFirestore.getInstance()
    private val referenceNames = mutableMapOf<Pair<String, String>, String?>()
    private val searchPages = linkedMapOf<String, Pair<Long, CatalogSearchPage>>()
    private var indexReadyAt = 0L
    private var indexReady = false
    fun clearSearchCache() { searchPages.clear(); indexReadyAt = 0L }
    fun clearReferenceCache() { referenceNames.clear() }

    suspend fun categories(after: DocumentSnapshot? = null): CategoryPage {
        FirestoreAccess.requireModule(AppModule.PRODUCTS)
        var query = db.collection("categorias").whereEqualTo("activo", true).limit(20)
        if (after != null) query = query.startAfter(after)
        val documents = query.get(Source.SERVER).inventoryAwait().documents
        return CategoryPage(documents.map { CatalogCategory(it.id, it.text("nombre") ?: it.id, it.text("descripcion")) },
            documents.lastOrNull(), documents.size == 20)
    }
    suspend fun categoryCount(): Long {
        FirestoreAccess.requireModule(AppModule.PRODUCTS)
        return db.collection("categorias").whereEqualTo("activo", true)
        .count().get(AggregateSource.SERVER).inventoryAwait().count
    }

    private fun categoryQuery(categoryId: String, filter: CatalogStockFilter): Query {
        val query = db.collection("productos").whereEqualTo("categoriaId", categoryId)
        return when (filter) {
            CatalogStockFilter.ALL -> query
            CatalogStockFilter.WITH_STOCK -> query.whereGreaterThan("stock", 0).orderBy("stock")
            CatalogStockFilter.WITHOUT_STOCK -> query.whereEqualTo("stock", 0)
        }
    }
    suspend fun categoryProducts(categoryId: String, filter: CatalogStockFilter, after: DocumentSnapshot? = null): CatalogPage {
        FirestoreAccess.requireModule(AppModule.PRODUCTS)
        var query = categoryQuery(categoryId, filter).limit(20)
        if (after != null) query = query.startAfter(after)
        val documents = query.get(Source.SERVER).inventoryAwait().documents
        return CatalogPage(documents.map { it.product() }, documents.lastOrNull(), documents.size == 20)
    }
    suspend fun categoryCounts(categoryId: String): CategoryCounts {
        FirestoreAccess.requireModule(AppModule.PRODUCTS)
        suspend fun count(filter: CatalogStockFilter) = categoryQuery(categoryId, filter)
            .count().get(AggregateSource.SERVER).inventoryAwait().count
        return CategoryCounts(count(CatalogStockFilter.ALL), count(CatalogStockFilter.WITH_STOCK), count(CatalogStockFilter.WITHOUT_STOCK))
    }

    suspend fun details(product: CatalogProduct): CatalogDetails {
        val errors = mutableListOf<String>()
        suspend fun name(collection: String, id: String?, previous: String?): String? {
            val referenceId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return previous?.trim()?.takeIf { it.isNotEmpty() }
            val key = collection to referenceId
            if (referenceNames.containsKey(key)) return referenceNames[key]
            return try {
                val document = db.collection(collection).document(referenceId).get(Source.SERVER).inventoryAwait()
                val value = document.getString("nombre")?.trim()?.takeIf { it.isNotEmpty() }
                referenceNames[key] = value
                value
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                errors += "No se pudo consultar $collection/$referenceId. ${inventoryError(e)}"
                null
            }
        }
        val category = name("categorias", product.categoryId, product.category)
        val brand = name("marcas", product.brandId, product.brand)
        return CatalogDetails(product.copy(category = category, brand = brand,
            description = product.description?.trim()?.takeIf { it.isNotEmpty() }),
            errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }
    suspend fun product(id: String): CatalogProduct? {
        val document = db.collection("productos").document(id).get(Source.SERVER).inventoryAwait()
        return if (document.exists()) document.product() else null
    }
    suspend fun count(withStock: Boolean): Long = stockQuery(withStock).count().get(AggregateSource.SERVER).inventoryAwait().count
    private fun stockQuery(withStock: Boolean): Query = if (withStock)
        db.collection("productos").whereGreaterThan("stock", 0).orderBy("stock")
    else db.collection("productos").whereEqualTo("stock", 0).orderBy(FieldPath.documentId())

    suspend fun page(withStock: Boolean, after: DocumentSnapshot? = null): CatalogPage {
        FirestoreAccess.requireModule(AppModule.PRODUCTS)
        var query = stockQuery(withStock).limit(25)
        if (after != null) query = query.startAfter(after)
        val documents = query.get(Source.SERVER).inventoryAwait().documents
        return CatalogPage(documents.map { it.product() }, documents.lastOrNull(), documents.size == 25)
    }

    suspend fun search(raw: String, after: CatalogSearchPage? = null): CatalogSearchPage {
        FirestoreAccess.requireModule(AppModule.PRODUCTS)
        val term = ProductSearch.normalize(raw)
        require(ProductSearch.eligible(term)) { "Escribe al menos una palabra de 3 caracteres." }
        val key = "$term|${after?.cursor?.id.orEmpty()}"
        val now = System.currentTimeMillis()
        searchPages[key]?.takeIf { now - it.first < 120_000 }?.let { return it.second }
        // Verify full coverage once per visit/TTL: never silently hide unindexed products.
        if (indexReadyAt == 0L || now - indexReadyAt >= 120_000) {
            val total = db.collection("productos").count().get(AggregateSource.SERVER).inventoryAwait().count
            val indexed = db.collection("productos").whereEqualTo("busquedaVersion", ProductSearch.VERSION)
                .count().get(AggregateSource.SERVER).inventoryAwait().count
            indexReady = total == indexed
            indexReadyAt = now
        }
        check(indexReady) { "La búsqueda por cualquier palabra necesita completar el índice de productos. El catálogo y los filtros de stock siguen disponibles." }
        var query = db.collection("productos").whereArrayContains("busquedaTrigramas", ProductSearch.anchor(term)).limit(25)
        if (after?.cursor != null) query = query.startAfter(after.cursor)
        val documents = query.get(Source.SERVER).inventoryAwait().documents
        val page = CatalogSearchPage(documents.map { it.product() }.filter { ProductSearch.matches(it.name, it.code, term) },
            documents.lastOrNull(), documents.size == 25)
        if (searchPages.size >= 32) searchPages.remove(searchPages.keys.first())
        searchPages[key] = System.currentTimeMillis() to page
        return page
    }

    suspend fun codes(productId: String, after: DocumentSnapshot? = null): CatalogCodePage {
        var query = db.collection("existencias").whereEqualTo("productoId", productId)
            .whereEqualTo("estado", "DISPONIBLE").limit(25)
        if (after != null) query = query.startAfter(after)
        val documents = query.get(Source.SERVER).inventoryAwait().documents
        return CatalogCodePage(documents.filter { (it.getLong("cantidad") ?: 0) > 0 }
            .mapNotNull { it.getString("codigo") }.distinct(), documents.lastOrNull(), documents.size == 25)
    }

    private fun DocumentSnapshot.product() = CatalogProduct(id, getString("codigo") ?: id,
        getString("nombre") ?: "Producto sin nombre", getLong("stock"), text("categoria"),
        text("marca"), text("descripcion"), text("categoriaId"), text("marcaId"))
    private fun DocumentSnapshot.text(field: String) = getString(field)?.trim()?.takeIf { it.isNotEmpty() }
}
