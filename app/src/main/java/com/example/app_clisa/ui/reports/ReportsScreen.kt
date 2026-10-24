package com.example.app_clisa.ui.reports

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.app_clisa.data.*
import com.example.app_clisa.ui.registration.RegisterIcon
import com.example.app_clisa.ui.registration.RegistrationBanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.File

private val White = Color(0xFFF1F5FF)
private val Muted = Color(0xFFABC7EC)
private val Blue = Color(0xFF0061FF)
private val Cyan = Color(0xFF00AAFF)
private val Green = Color(0xFF00DF98)
private val Purple = Color(0xFFAB36FF)
private val Orange = Color(0xFFFFAB13)
private val Border = Color(0xFF07568B)
private val Background = Color(0xFF020E21)
private val locale = Locale.forLanguageTag("es-PE")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(onBack: () -> Unit, authenticated: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val repository = remember { ReportRepository() }
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
    var startText by rememberSaveable { mutableStateOf(LocalDate.now(reportZone).minusDays(6).toString()) }
    var endText by rememberSaveable { mutableStateOf(LocalDate.now(reportZone).toString()) }
    val start = LocalDate.parse(startText)
    val end = LocalDate.parse(endText)
    var page by remember { mutableStateOf<ReportPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var requestVersion by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(ReportFilter()) }
    var interval by remember { mutableStateOf(ReportInterval.DAY) }
    var datesOpen by remember { mutableStateOf(false) }
    var allProducts by remember { mutableStateOf(false) }
    var allUsers by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var exportFileName by rememberSaveable { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val fileName = exportFileName
        exportFileName = ""
        scope.launch {
            val file = fileName.takeIf { it.startsWith("clisa-report-") && File(it).name == it }?.let { File(context.cacheDir, it) }
            try {
                if (uri != null) {
                    exporting = true
                    withContext(Dispatchers.IO) {
                        check(file != null && file.isFile) { "Vuelve a generar el reporte para guardarlo." }
                        checkNotNull(context.contentResolver.openOutputStream(uri)) { "No se pudo abrir el archivo." }
                            .use { output -> file.inputStream().use { it.copyTo(output) } }
                    }
                    notice = "Reporte CSV guardado."
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "No se pudo guardar el archivo. ${e.message.orEmpty()}" }
            finally { file?.delete(); exporting = false }
        }
    }
    BackHandler { if (!exporting) onBack() }
    LaunchedEffect(authenticated, startText, endText, reload) {
        if (!authenticated) return@LaunchedEffect
        val version = ++requestVersion
        loading = true; error = null; page = null
        try { page = repository.page(start, end) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = inventoryError(e) }
        finally { if (requestVersion == version) loading = false }
    }
    fun more() {
        val old = page ?: return
        if (loading || !old.hasMore) return
        val currentStart = startText; val currentEnd = endText; val generation = reload
        val version = ++requestVersion
        loading = true; error = null
        scope.launch {
            try {
                val next = repository.page(start, end, old.cursor)
                if (startText == currentStart && endText == currentEnd && reload == generation)
                    page = next.copy(records = (old.records + next.records).distinctBy { it.id })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (requestVersion == version) error = inventoryError(e) }
            finally { if (requestVersion == version) loading = false }
        }
    }
    val records = page?.records.orEmpty()
    val filtered = remember(records, filter) { ReportAnalysis.filter(records, filter) }
    val totals = remember(filtered) { ReportAnalysis.totals(filtered) }
    val rankedProducts = remember(filtered) { ReportAnalysis.products(filtered) }
    val rankedUsers = remember(filtered) { ReportAnalysis.users(filtered) }
    val stores = remember(filtered) { ReportAnalysis.stores(filtered) }
    val buckets = remember(filtered, interval, start, end) { ReportAnalysis.dates(filtered, start, end, interval) }
    val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, onPrimary = White, surface = Color(0xFF031A34), onSurface = White, onSurfaceVariant = Muted, outline = Border)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Box(Modifier.fillMaxWidth()) {
                    RegistrationBanner(Modifier.matchParentSize(), showScanner = false)
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack, enabled = !exporting) { RegisterIcon("back", White, "Volver") }
                            Text("Reportes", color = White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { filter = ReportFilter() }, enabled = authenticated) { ReportSymbol("filter", White, "Restablecer filtros") }
                        }
                        Text("Consulta y analiza la información\nde tus movimientos", color = Muted, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, end = 70.dp, bottom = 14.dp))
                    }
                }
                if (!authenticated) Text("Inicia sesión para consultar reportes reales.", color = Muted, modifier = Modifier.padding(20.dp))
                else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(onClick = { datesOpen = true }, enabled = !loading && !exporting, shape = RoundedCornerShape(10.dp), color = Color(0xFF062343), border = BorderStroke(1.dp, Border)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RegisterIcon("calendar", White, null)
                            Text("${start.format(dateFormat)} - ${end.format(dateFormat)}", color = White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            RegisterIcon("down", Muted, null, 18.dp)
                        }
                    }
                    val storeOptions = records.flatMap { listOf(it.originId to it.origin, it.destinationId to it.destination) }.filter { it.first.isNotEmpty() }.distinctBy { it.first }.sortedBy { it.second }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportSelect("Todas las tiendas", "store", filter.storeId, storeOptions, Modifier.weight(1f)) { filter = filter.copy(storeId = it) }
                        ReportSelect("Todos los productos", "product", filter.productId, records.distinctBy { it.productId }.map { it.productId to "${it.product} · ${it.code}" }.sortedBy { it.second }, Modifier.weight(1f)) { filter = filter.copy(productId = it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportSelect("Todos los usuarios", "user", filter.userId, records.distinctBy { it.userId }.map { it.userId to it.user }.sortedBy { it.second }, Modifier.weight(1f)) { filter = filter.copy(userId = it) }
                        ReportSelect("Todos los estados", "payment", filter.payment, listOf("Pagado" to "Pagado", "Pendiente" to "Pendiente", "SIN_DATO" to "No registrado"), Modifier.weight(1f)) { filter = filter.copy(payment = it) }
                    }
                    Text("Salidas completadas · opciones según el período cargado", color = Muted, style = MaterialTheme.typography.labelSmall)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan)
                    error?.let { message ->
                        Text(message, color = Color(0xFFFF536F), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { if (page?.hasMore == true) more() else reload++ }, enabled = !loading) { Text("Reintentar") }
                    }
                    if (page?.hasMore == true) {
                        Text("Reporte parcial: ${records.size} salidas cargadas. Quedan registros del período por revisar.", color = Orange, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { more() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Cargar los siguientes 100 registros") }
                    }
                    if (!loading && page != null && filtered.isEmpty()) Text("No hay salidas que coincidan con este período y filtros.", color = Muted, modifier = Modifier.padding(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportMetric("Total de salidas", if (page == null) "—" else totals.exits.toString(), "Movimientos registrados", "product", Cyan, Modifier.weight(1f))
                        ReportMetric("Unidades retiradas", if (page == null) "—" else totals.units.toString(), "Productos en total", "exit", Green, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportMetric("Pagados", if (page == null) "—" else totals.paid.toString(), "Registros con pago", "payment", Purple, Modifier.weight(1f))
                        ReportMetric("Pendientes", if (page == null) "—" else totals.pending.toString(), "Registros pendientes", "clock", Orange, Modifier.weight(1f))
                    }
                    if (totals.paymentUnknown > 0) Text("${totals.paymentUnknown} salidas sin estado de pago registrado.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    ReportCard {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RegisterIcon("calendar", Cyan, null, 22.dp)
                            Text("Movimientos por fecha", color = White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            ReportInterval.entries.forEach { value ->
                                val label = when (value) { ReportInterval.DAY -> "Día"; ReportInterval.WEEK -> "Semana"; ReportInterval.MONTH -> "Mes" }
                                Surface(onClick = { interval = value }, shape = RoundedCornerShape(7.dp), color = if (interval == value) Blue else Color(0xFF082445)) {
                                    Text(label, color = White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 7.dp))
                                }
                            }
                        }
                        DateChart(buckets, interval)
                    }
                    ReportCard {
                        ReportCardTitle("product", "Productos más retirados", allProducts) { allProducts = !allProducts }
                        ReportTableHeader(listOf("#", "Producto", "Código", "Cantidad"), listOf(.35f, 2f, 1.1f, .7f))
                        (if (allProducts) rankedProducts else rankedProducts.take(5)).forEachIndexed { index, item ->
                            ReportTableRow(listOf((index + 1).toString(), item.name, item.code, item.quantity.toString()), listOf(.35f, 2f, 1.1f, .7f))
                        }
                        if (rankedProducts.isEmpty()) ChartEmpty()
                    }
                    ReportCard {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RegisterIcon("store", Cyan, null, 24.dp)
                            Text("Movimientos por tienda", color = White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        }
                        StoreChart(stores)
                        Text("Una transferencia cuenta como salida en origen y entrada en destino.", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    ReportCard {
                        ReportCardTitle("user", "Actividad de usuarios", allUsers) { allUsers = !allUsers }
                        ReportTableHeader(listOf("#", "Usuario", "Rol", "Movimientos"), listOf(.3f, 1.8f, 1f, 1f))
                        (if (allUsers) rankedUsers else rankedUsers.take(3)).forEachIndexed { index, user ->
                            ReportTableRow(listOf((index + 1).toString(), user.name, "—", user.movements.toString()), listOf(.3f, 1.8f, 1f, 1f))
                        }
                        if (rankedUsers.isEmpty()) ChartEmpty()
                        Text("Los registros no incluyen el rol histórico del usuario.", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(10.dp))
                    }
                    var exportMenu by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth()) {
                        Button(onClick = { exportMenu = true }, enabled = page != null && page?.hasMore == false && error == null && !loading && !exporting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp)) {
                            ReportSymbol("document", White, null); Spacer(Modifier.width(12.dp)); Text(if (exporting) "Guardando…" else "Generar reporte", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(24.dp)); RegisterIcon("down", White, null, 18.dp)
                        }
                        DropdownMenu(exportMenu, onDismissRequest = { exportMenu = false }) {
                            listOf(false to "Movimientos CSV", true to "Resumen CSV").forEach { (summary, title) ->
                                DropdownMenuItem(text = { Text(title) }, onClick = {
                                    exportMenu = false
                                    exporting = true
                                    val chosenRows = filtered.toList(); val chosenFilter = filter
                                    scope.launch {
                                        try {
                                            val file = withContext(Dispatchers.IO) {
                                                File.createTempFile("clisa-report-", ".csv", context.cacheDir).also {
                                                    it.writeText(ReportAnalysis.csv(chosenRows, start, end, summary, chosenFilter), Charsets.UTF_8)
                                                }
                                            }
                                            exportFileName = file.name
                                            exportLauncher.launch("CLISA_${if (summary) "resumen" else "movimientos"}_${start}_${end}.csv")
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { exporting = false; notice = "No se pudo preparar el archivo. ${e.message.orEmpty()}" }
                                    }
                                })
                            }
                        }
                    }
                    if (page?.hasMore == true) Text("Completa la carga del período para exportar un reporte completo.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (datesOpen) {
            ReportDateRangeDialog(start, end, onDismiss = { datesOpen = false }) { pickedStart, pickedEnd ->
                startText = pickedStart.toString(); endText = pickedEnd.toString()
                filter = ReportFilter(); datesOpen = false
            }
        }
        notice?.let { AlertDialog(onDismissRequest = { notice = null }, title = { Text("Reportes") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("Entendido") } }) }
    }
}
