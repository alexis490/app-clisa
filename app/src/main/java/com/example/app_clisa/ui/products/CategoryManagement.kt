package com.example.app_clisa.ui.products

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app_clisa.data.*
import com.example.app_clisa.ui.registration.RegisterIcon
import com.example.app_clisa.ui.registration.RegistrationBanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TextWhite = Color(0xFFF1F5FF)
private val TextMuted = Color(0xFFABC7EC)
private val CardBorder = Color(0xFF07568B)
private val ActionBlue = Color(0xFF0061FF)

internal class CategoryFeed {
    var page by mutableStateOf<CatalogPage?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val scroll = LazyListState()
}

/** Retained by the existing ProductsScreen while its unchanged detail is open. */
internal class CategoryManagementState {
    var categories by mutableStateOf<CategoryPage?>(null)
    var categoryCount by mutableStateOf<Long?>(null)
    var categoriesLoading by mutableStateOf(false)
    var categoriesError by mutableStateOf<String?>(null)
    var category by mutableStateOf<CatalogCategory?>(null)
    var categoryQuery by mutableStateOf("")
    var productQuery by mutableStateOf("")
    var appliedQuery by mutableStateOf("")
    var filter by mutableStateOf(CatalogStockFilter.ALL)
    var countsError by mutableStateOf<String?>(null)
    var countsLoading by mutableStateOf(false)
    val counts = mutableStateMapOf<String, CategoryCounts>()
    val feeds = mutableMapOf<Pair<String, CatalogStockFilter>, CategoryFeed>()
    val categoryScroll = LazyListState()
    fun feed(id: String) = feeds.getOrPut(id to filter) { CategoryFeed() }
}

@Composable
internal fun CategoryManagement(catalog: ProductCatalog, state: CategoryManagementState, authenticated: Boolean,
    onBack: () -> Unit, onProduct: (CatalogProduct) -> Unit) {
    val scope = rememberCoroutineScope()
    val category = state.category
    val filter = state.filter
    val feed = category?.let { state.feed(it.id) }
    fun leave() {
        if (state.category != null) {
            state.category = null; state.productQuery = ""; state.appliedQuery = ""
        } else onBack()
    }
    BackHandler { leave() }
    suspend fun categories(more: Boolean = false) {
        if (state.categoriesLoading) return
        state.categoriesLoading = true; state.categoriesError = null
        try {
            val old = state.categories
            val next = catalog.categories(if (more) old?.cursor else null)
            state.categories = if (more) next.copy(categories = (old?.categories.orEmpty() + next.categories).distinctBy { it.id }) else next
            if (state.categoryCount == null) state.categoryCount = catalog.categoryCount()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.categoriesError = inventoryError(e) }
        finally { state.categoriesLoading = false }
    }
    suspend fun products(more: Boolean = false) {
        val chosen = category ?: return
        val target = feed ?: return
        if (target.loading) return
        target.loading = true; target.error = null
        try {
            val old = target.page
            val next = catalog.categoryProducts(chosen.id, filter, if (more) old?.cursor else null)
            target.page = if (more) next.copy(products = (old?.products.orEmpty() + next.products).distinctBy { it.id }) else next
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { target.error = inventoryError(e) }
        finally { target.loading = false }
    }
    LaunchedEffect(authenticated) {
        if (authenticated && state.categories == null) categories()
    }
    LaunchedEffect(authenticated, category?.id, state.filter) {
        if (authenticated && feed != null && feed.page == null) products()
    }
    LaunchedEffect(authenticated, category?.id) {
        val chosen = category ?: return@LaunchedEffect
        state.countsError = null
        if (!authenticated || state.counts.containsKey(chosen.id)) return@LaunchedEffect
        state.countsLoading = true; state.countsError = null
        try { state.counts[chosen.id] = catalog.categoryCounts(chosen.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.countsError = inventoryError(e) }
        finally { state.countsLoading = false }
    }
    LaunchedEffect(ProductSearch.normalize(state.productQuery)) {
        val term = ProductSearch.normalize(state.productQuery)
        if (term.isNotEmpty()) delay(350)
        state.appliedQuery = term
    }
    val listState = feed?.scroll ?: state.categoryScroll
    val nearEnd by remember(listState) { derivedStateOf {
        val info = listState.layoutInfo
        listState.isScrollInProgress && info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 3
    } }
    LaunchedEffect(nearEnd, category?.id, state.filter) {
        if (!authenticated || !nearEnd) return@LaunchedEffect
        if (category == null && state.categories?.hasMore == true && state.categoriesError == null) categories(true)
        else if (feed?.page?.hasMore == true && feed.error == null) products(true)
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth()) {
            RegistrationBanner(Modifier.matchParentSize(), showScanner = false)
            Column(Modifier.padding(bottom = 14.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { leave() }, modifier = Modifier.size(52.dp)) { RegisterIcon("back", TextWhite, "Volver") }
                    Text(category?.name ?: "Gestión de productos", color = TextWhite, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Text(if (category == null) "Consulta el catálogo y stock de artículos" else "Catálogo de productos y stock disponible",
                    color = TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 52.dp, end = 12.dp))
            }
        }
        if (!authenticated) {
            Text("Inicia sesión para consultar las categorías y productos reales.", color = TextMuted, modifier = Modifier.padding(20.dp))
            return@Column
        }
        OutlinedTextField(if (category == null) state.categoryQuery else state.productQuery,
            { if (category == null) state.categoryQuery = it else state.productQuery = it },
            placeholder = { Text(if (category == null) "Buscar categoría…" else "Buscar en esta categoría…", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { RegisterIcon("search", TextMuted, null) }, singleLine = true, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), textStyle = MaterialTheme.typography.bodyMedium)
        if (category == null) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)) {
                val compact = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.15f
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Categorías", color = TextWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            modifier = if (compact) Modifier.weight(1f) else Modifier)
                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF062C69), border = BorderStroke(.5.dp, CardBorder)) {
                            Text(state.categoryCount?.toString() ?: "—", color = TextWhite, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                        if (!compact) { Spacer(Modifier.weight(1f)); ExcelImportAction() }
                    }
                    if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ExcelImportAction() }
                }
            }
            val visible = state.categories?.categories.orEmpty().filter { ProductSearch.matches(it.name, it.id, state.categoryQuery) ||
                ProductSearch.normalize(state.categoryQuery).isEmpty() || ProductSearch.matches(it.description.orEmpty(), it.id, state.categoryQuery) }
            LazyColumn(state = state.categoryScroll, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(visible, key = { it.id }) { item -> CategoryRow(item) {
                    state.category = item; state.productQuery = ""; state.appliedQuery = ""; state.filter = CatalogStockFilter.ALL
                } }
                item {
                    state.categoriesError?.let { Text(it, color = Color(0xFFFF536F), style = MaterialTheme.typography.bodySmall) }
                    if (state.categoriesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (state.categoriesError != null) TextButton(onClick = { scope.launch { categories(state.categories != null) } }) { Text("Reintentar") }
                    else if (state.categories?.hasMore == true) TextButton(onClick = { scope.launch { categories(true) } }, modifier = Modifier.fillMaxWidth()) { Text("Cargar más categorías") }
                    else if (visible.isEmpty()) Text("No hay categorías activas que coincidan.", color = TextMuted, modifier = Modifier.padding(12.dp))
                }
            }
        } else {
            val counts = state.counts[category.id]
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StockChip("Todos", counts?.all, state.filter == CatalogStockFilter.ALL, Modifier.weight(1f)) { state.filter = CatalogStockFilter.ALL }
                StockChip("Con stock", counts?.withStock, state.filter == CatalogStockFilter.WITH_STOCK, Modifier.weight(1f)) { state.filter = CatalogStockFilter.WITH_STOCK }
                StockChip("Sin stock", counts?.withoutStock, state.filter == CatalogStockFilter.WITHOUT_STOCK, Modifier.weight(1f)) { state.filter = CatalogStockFilter.WITHOUT_STOCK }
            }
            val searching = state.appliedQuery.isNotEmpty()
            val visible = feed?.page?.products.orEmpty().filter { !searching || ProductSearch.matches(it.name, it.code, state.appliedQuery) }
            val pending = feed?.page?.hasMore == true
            if (searching) Text("Busca en los productos cargados de esta categoría.${if (pending) " Quedan páginas por revisar." else ""}",
                color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(visible, key = { it.id }) { product ->
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF02182E), border = BorderStroke(1.dp, CardBorder)) {
                        ProductRow(product.copy(category = product.category ?: category.name)) { onProduct(product) }
                    }
                }
                item {
                    (feed?.error ?: state.countsError)?.let { Text(it, color = Color(0xFFFF536F), style = MaterialTheme.typography.bodySmall) }
                    if (feed?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (feed?.error != null) TextButton(onClick = { scope.launch { products(feed.page != null) } }) { Text("Reintentar productos") }
                    else if (pending) TextButton(onClick = { scope.launch { products(true) } }, modifier = Modifier.fillMaxWidth()) { Text("Cargar los siguientes 20 productos") }
                    else if (visible.isEmpty()) Text(if (searching) "No hay coincidencias en los productos cargados." else "No hay productos en esta selección.", color = TextMuted, modifier = Modifier.padding(12.dp))
                    if (state.countsError != null && !state.countsLoading) TextButton(onClick = { scope.launch {
                        state.countsLoading = true
                        try { state.counts[category.id] = catalog.categoryCounts(category.id); state.countsError = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { state.countsError = inventoryError(e) }
                        finally { state.countsLoading = false }
                    } }) { Text("Reintentar contadores") }
                }
            }
        }
    }
}

@Composable
private fun StockChip(label: String, count: Long?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp), color = if (selected) ActionBlue else Color(0xFF02182E),
        border = BorderStroke(1.dp, if (selected) Color(0xFF009EFF) else CardBorder)) {
        Box(Modifier.heightIn(min = 42.dp).padding(horizontal = 5.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text("$label (${count?.toString() ?: "—"})", color = if (selected) TextWhite else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CategoryRow(category: CatalogCategory, onClick: () -> Unit) {
    val label = ProductSearch.normalize(category.name)
    val index = when {
        "laptop" in label -> 1
        "audif" in label || "audio" in label || "parlante" in label -> 2
        "cable" in label || "adaptador" in label -> 3
        "camara" in label -> 4
        "cargador" in label || "fuente" in label -> 5
        "case" in label || "gabinete" in label -> 6
        else -> 0
    }
    val colors = listOf(Color(0xFF0061FF), Color(0xFF6346E8), Color(0xFF008B61), Color(0xFFFF9A30), Color(0xFFD8B526), Color(0xFFE72A44), Color(0xFF00B8CF), Color(0xFF0643D9))
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Color.Transparent, border = BorderStroke(1.dp, CardBorder)) {
        Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF052550), Color(0xFF021326)))).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(54.dp), shape = RoundedCornerShape(10.dp), color = colors[index]) {
                CategorySymbol(category.name)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(category.name, color = TextWhite, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                category.description?.let { Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
            }
            RegisterIcon("right", TextWhite, null, 20.dp)
        }
    }
}

@Composable
private fun CategorySymbol(name: String) {
    Canvas(Modifier.padding(12.dp)) {
        val s = size.width / 24f
        val stroke = Stroke(1.7f * s)
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(TextWhite, Offset(a*s, b*s), Offset(c*s, d*s), stroke.width, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(TextWhite, Offset(x*s, y*s), Size(w*s, h*s), CornerRadius(1.5f*s), style = stroke)
        val label = ProductSearch.normalize(name)
        when {
            "laptop" in label -> { rect(3f, 3f, 18f, 14f); line(3f, 17f, 1f, 21f); line(1f, 21f, 23f, 21f); line(23f, 21f, 21f, 17f) }
            "audio" in label || "audif" in label || "parlante" in label -> {
                drawArc(TextWhite, 180f, 180f, false, Offset(2*s, 2*s), Size(20*s, 24*s), style = stroke)
                rect(2f, 12f, 4f, 9f); rect(18f, 12f, 4f, 9f)
            }
            "cable" in label || "adaptador" in label -> {
                drawPath(Path().apply { moveTo(8*s, 3*s); cubicTo(22*s, 1*s, 22*s, 12*s, 12*s, 12*s); cubicTo(1*s, 12*s, 2*s, 23*s, 16*s, 21*s) }, TextWhite, style = stroke)
                rect(6f, 1f, 4f, 4f); rect(14f, 19f, 4f, 4f)
            }
            "camara" in label -> { rect(2f, 4f, 20f, 13f); drawCircle(TextWhite, 4*s, Offset(12*s, 10*s), style = stroke); line(12f, 17f, 12f, 22f); line(6f, 22f, 18f, 22f) }
            "cargador" in label || "fuente" in label -> { rect(5f, 7f, 14f, 10f); line(9f, 2f, 9f, 7f); line(15f, 2f, 15f, 7f); line(12f, 17f, 12f, 23f) }
            "case" in label || "gabinete" in label -> { rect(5f, 1f, 14f, 22f); rect(8f, 4f, 8f, 3f); rect(8f, 10f, 8f, 3f); drawCircle(TextWhite, 2*s, Offset(12*s, 18*s), style = stroke) }
            else -> { rect(1f, 3f, 22f, 15f); line(8f, 22f, 16f, 22f); line(12f, 18f, 12f, 22f); line(3f, 15f, 21f, 15f) }
        }
    }
}
