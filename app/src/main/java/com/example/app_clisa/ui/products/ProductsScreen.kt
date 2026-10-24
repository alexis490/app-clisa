package com.example.app_clisa.ui.products

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.app_clisa.data.*
import com.example.app_clisa.ui.registration.RegisterIcon
import com.example.app_clisa.ui.registration.RegistrationBanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val White = Color(0xFFF1F5FF)
private val Muted = Color(0xFFABC7EC)
private val Cyan = Color(0xFF00A8FF)
private val Green = Color(0xFF00DF8D)
private val Red = Color(0xFFFF1744)
private val Border = Color(0xFF07568B)
private val Background = Color(0xFF020E21)

@Composable
fun ProductsScreen(onBack: () -> Unit, authenticated: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = (context as? Activity)?.let { WindowCompat.getInsetsController(it.window, view) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            status?.let { controller.isAppearanceLightStatusBars = it }
            navigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
    val catalog = remember { ProductCatalog() }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<CatalogProduct?>(null) }
    var details by remember { mutableStateOf<CatalogDetails?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val categoryState = remember { CategoryManagementState() }
    val loading = false
    var error by remember { mutableStateOf<String?>(null) }
    var codePage by remember { mutableStateOf<CatalogCodePage?>(null) }
    var codesLoading by remember { mutableStateOf(false) }
    var codesError by remember { mutableStateOf<String?>(null) }
    val codeCache = remember { mutableMapOf<String, CatalogCodePage>() }
    fun leave() { if (selected != null) selected = null else onBack() }
    BackHandler { leave() }
    LaunchedEffect(selected?.id, selected?.categoryId, selected?.brandId, selected?.category, selected?.brand, refresh) {
        details = null
        val product = selected ?: return@LaunchedEffect
        details = catalog.details(product)
    }
    LaunchedEffect(selected?.id, refresh) {
        val product = selected ?: return@LaunchedEffect
        codePage = codeCache[product.id]; codesError = null
        if (codePage != null) return@LaunchedEffect
        codesLoading = true
        try {
            if (refresh > 0) {
                val updated = catalog.product(product.id)
                if (updated == null) { selected = null; error = "El producto ya no está disponible."; return@LaunchedEffect }
                selected = updated
            }
            codePage = catalog.codes(product.id)
            codeCache[product.id] = codePage!!
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { codesError = inventoryError(e) }
        finally { codesLoading = false }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, onPrimary = White, surface = Color(0xFF031A34),
        onSurface = White, onSurfaceVariant = Muted, outline = Border)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                if (selected != null) Box(Modifier.fillMaxWidth()) {
                    RegistrationBanner(Modifier.matchParentSize(), showScanner = false)
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { leave() }) { RegisterIcon("back", White, "Volver") }
                            if (selected != null) Text("Detalle de producto", color = White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            else Spacer(Modifier.weight(1f))
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = authenticated && !loading && !codesLoading) { ProductGlyph("menu", "Opciones") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Actualizar") }, onClick = { menu = false; codeCache.clear(); catalog.clearReferenceCache(); catalog.clearSearchCache(); refresh++ })
                                }
                            }
                        }
                        if (selected == null) Text("Gestión de productos", color = White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                        Text(if (selected == null) "Consulta el catálogo y stock de artículos" else "Información del producto y sus códigos disponibles",
                            color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp))
                    }
                }
                if (!authenticated) Text("Inicia sesión para consultar el inventario real.", color = Muted, modifier = Modifier.padding(20.dp))
                else if (selected != null) {
                    val resolved = details?.takeIf { it.product.id == selected!!.id }
                    val detailProduct = resolved?.let { selected!!.copy(category = it.product.category, brand = it.product.brand) } ?: selected!!
                    ProductDetail(detailProduct, codePage, codesLoading, codesError, detailsError = resolved?.error, onMore = {
                        val product = selected!!
                        val old = codePage
                        if (old != null && !codesLoading) {
                            codesLoading = true
                            scope.launch {
                                try {
                                    val next = catalog.codes(product.id, old.cursor)
                                    val combined = next.copy(codes = (old.codes + next.codes).distinct())
                                    codeCache[product.id] = combined
                                    if (selected?.id == product.id) codePage = combined
                                    codesError = null
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { codesError = inventoryError(e) }
                                finally { codesLoading = false }
                            }
                        }
                    })
                } else CategoryManagement(catalog, categoryState, authenticated, onBack, onProduct = { selected = it })
            }
        }
    }
}

@Composable
private fun CatalogCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color.Transparent, border = BorderStroke(1.dp, Border)) {
        Column(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF031C34), Color(0xFF02152B)))), content = content)
    }
}

@Composable
private fun StockSummary(label: String, count: Long?, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color(0xFF032044), border = BorderStroke(1.dp, Border)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RegisterIcon("product", accent, null, 34.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(label, color = White, style = MaterialTheme.typography.bodyMedium)
                Text(count?.toString() ?: "—", color = White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            RegisterIcon("right", White, null, 18.dp)
        }
    }
}

@Composable
private fun StockGroup(title: String, count: Long?, accent: Color, expanded: Boolean, products: List<CatalogProduct>, hasMore: Boolean,
    loading: Boolean, onToggle: () -> Unit, onMore: () -> Unit, onSelect: (CatalogProduct) -> Unit, emptyMessage: String) {
    CatalogCard {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            RegisterIcon("product", accent, null, 28.dp)
            Text(title, color = White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 10.dp))
            count?.let { Text("($it)", color = accent, style = MaterialTheme.typography.titleSmall) }
            if (expanded) ProductGlyph("up", null) else RegisterIcon("down", White, null, 24.dp)
        }
        if (expanded) {
            products.forEach { product ->
                HorizontalDivider(color = Border, thickness = .5.dp)
                ProductRow(product) { onSelect(product) }
            }
            if (products.isEmpty() && !loading) Text(emptyMessage, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            if (hasMore) TextButton(onClick = onMore, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Cargar más") }
        }
    }
}

@Composable
internal fun ProductRow(product: CatalogProduct, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(60)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(product.name, color = White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("Código: ${product.code}", color = Muted, style = MaterialTheme.typography.bodySmall)
                product.category?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            }
            StockBadge(product.stock)
            RegisterIcon("right", White, null, 18.dp, Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun ProductImage(size: Int) {
    Surface(Modifier.size(size.dp), shape = RoundedCornerShape(10.dp), color = Color(0xFFE1EAF7)) {
        Box(contentAlignment = Alignment.Center) { RegisterIcon("product", Color(0xFF0863BD), "Producto", (size / 2).dp) }
    }
}

@Composable
private fun StockBadge(stock: Long?) {
    val color = if (stock == null) Border else if (stock > 0) Color(0xFF008B59) else Color(0xFFE51036)
    Surface(shape = RoundedCornerShape(12.dp), color = color) {
        Text(stock?.let { "$it un." } ?: "—", color = White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ProductDetail(product: CatalogProduct, page: CatalogCodePage?, loading: Boolean, error: String?, detailsError: String?, onMore: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogCard {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ProductImage(100)
                Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(product.name, color = White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFF00276A), border = BorderStroke(1.dp, Cyan)) {
                        Text("Código principal: ${product.code}", color = Cyan, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                    Text("Categoría: ${product.category ?: "No registrada"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("Marca: ${product.brand ?: "No registrada"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        CatalogCard {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                RegisterIcon("quantity", Muted, null, 34.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Stock total", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text(product.stock?.let { "$it unidades" } ?: "No registrado", color = White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Surface(shape = RoundedCornerShape(18.dp), color = if ((product.stock ?: 0) > 0) Green else Red) {
                    Text(if (product.stock == null) "Sin dato" else if (product.stock > 0) "Con stock" else "Sin stock", color = Background,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
        }
        CatalogCard {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RegisterIcon("barcode", White, null, 26.dp)
                    Text("Códigos disponibles", color = White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                    Text(" (${page?.codes?.size ?: 0}${if (page?.hasMore == true) "+" else ""})", color = Muted)
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan)
                error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                page?.codes.orEmpty().forEach { code ->
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF062343), border = BorderStroke(.5.dp, Border)) {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RegisterIcon("barcode", White, null)
                            Text(code, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { ProductGlyph("copy", "Copiar código $code") }
                        }
                    }
                }
                if (!loading && error == null && page?.codes.isNullOrEmpty()) Text("No hay códigos disponibles.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (page?.hasMore == true) TextButton(onClick = onMore, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Cargar más códigos") }
            }
        }
        CatalogCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProductGlyph("document", null)
                    Text("Información adicional", color = White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                }
                Text("Descripción: ${product.description ?: "No registrada"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("Categoría: ${product.category ?: "No registrada"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("Marca: ${product.brand ?: "No registrada"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                detailsError?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ProductGlyph(kind: String, description: String?) {
    Canvas(Modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        val scale = size.width / 24f
        val stroke = Stroke(1.8f * scale)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(White, Offset(x * scale, y * scale), Offset(xx * scale, yy * scale), stroke.width, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(White, Offset(x * scale, y * scale), Size(w * scale, h * scale), CornerRadius(1.5f * scale), style = stroke)
        when (kind) {
            "menu" -> listOf(5f, 12f, 19f).forEach { drawCircle(White, 1.5f * scale, Offset(12f * scale, it * scale)) }
            "up" -> { line(6f, 15f, 12f, 9f); line(12f, 9f, 18f, 15f) }
            "copy" -> { rect(8f, 7f, 12f, 15f); line(15f, 3f, 4f, 3f); line(4f, 3f, 4f, 17f) }
            "document" -> { rect(4f, 2f, 16f, 20f); line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 14f, 16f) }
        }
    }
}
