package com.example.app_clisa.ui.registration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.example.app_clisa.data.*
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.util.UUID

private val Navy = Color(0xFFF2F6FF)
private val Muted = Color(0xFF9ABDE7)
private val FieldBorder = Color(0xFF245D8C)
private val ActionBlue = Color(0xFF0061FF)
private val PageBackground = Color(0xFF020E21)
private val InputBackground = Color(0xFF072443)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegisterScreen(onBack: () -> Unit, demo: Boolean = false) {
    MaterialTheme(colorScheme = darkColorScheme(primary = ActionBlue, onPrimary = Color.White,
        surface = Color(0xFF041A31), onSurface = Navy, onSurfaceVariant = Muted,
        secondary = Muted, outline = FieldBorder, error = Color(0xFFFFA4AF))) {
        RegisterContent(onBack, demo)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegisterContent(onBack: () -> Unit, demo: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = (context as? Activity)?.let { WindowCompat.getInsetsController(it.window, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            oldStatus?.let { controller.isAppearanceLightStatusBars = it }
            oldNavigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
    val store = remember(context) { ExitStore(context) }
    val scope = rememberCoroutineScope()
    val remote = remember { FirestoreInventory() }
    var live by remember { mutableStateOf(InventoryData()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(demo, reload) {
        if (!demo) {
            live = InventoryData()
            try { live = remote.loadReferenceData(refreshCredentials = reload > 0) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { live = InventoryData(loading = false, error = inventoryError(e)) }
        }
    }
    var input by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var match by remember { mutableStateOf<CodeMatch?>(null) }
    var cart by rememberSaveable { mutableStateOf(arrayListOf<PendingExit>()) }
    var receiverId by rememberSaveable { mutableStateOf("") }
    var receiverName by rememberSaveable { mutableStateOf("") }
    var payment by rememberSaveable { mutableStateOf("") }
    // Freeze submission details after the first attempt: retries retain the same record IDs and payloads.
    var submitted by rememberSaveable { mutableStateOf(false) }
    var submittedName by rememberSaveable { mutableStateOf("") }
    var completed by rememberSaveable { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var lookupError by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var loadingProduct by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var lookupJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val busy = saving || scanning || loadingProduct || adding
    val receiverOptions = if (demo) DemoInventory.receivers.map { it.name } else live.receivers.map { candidate ->
        if (live.receivers.count { it.name == candidate.name } > 1) "${candidate.name} · ${candidate.id}" else candidate.name
    }
    val liveReceiver = live.receivers.find { it.id == receiverId }
    val receiverDisplay = if (demo) receiverName else liveReceiver?.let { receiverOptions[live.receivers.indexOf(it)] }.orEmpty()
    val destination = if (demo) DemoInventory.destinationStoreName else live.stores.find { it.id == "toqen" }?.name ?: "Cargando TOQEN…"
    val ready = demo || (!live.loading && live.error == null && live.stores.count { it.active } == 2)
    val product = match?.product
    val reserved = cart.filter { it.productId == product?.id }.sumOf { it.quantity }
    val maxQuantity = ((product?.stock ?: 0) - reserved).coerceAtLeast(0)
    fun leave() {
        if (cart.isNotEmpty() || input.isNotBlank() || submitted) discard = true else onBack()
    }
    BackHandler { if (!busy) leave() }
    fun lookup(raw: String) {
        if (busy || submitted) return
        val code = raw.trim()
        if (code.isEmpty()) { lookupError = "Ingresa el código del producto."; return }
        lookupJob?.cancel()
        input = code
        match = null
        lookupError = null
        loadingProduct = true
        lookupJob = scope.launch {
            try {
                match = if (demo) DemoInventory.find(code)?.let { item ->
                    val free = item.units.filter { it !in store.usedCodes() }
                    CodeMatch(LiveProduct(item.code, item.code, item.name, free.size.toLong()))
                } else remote.resolveCode(code)
                if (match == null) lookupError = "Producto no encontrado"
                quantity = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { lookupError = inventoryError(e) }
            finally { loadingProduct = false }
        }
    }
    fun setQuantity(value: String) {
        if (value.all { it in '0'..'9' } && value.length <= 9) quantity = value
    }
    fun addProduct() {
        val selected = match ?: run { message = "Busca o escanea un producto primero."; return }
        val amount = quantity.toLongOrNull()
        if (amount == null || amount <= 0) { message = "Ingresa una cantidad entera mayor que cero."; return }
        if (amount > maxQuantity) { message = "La cantidad supera el stock disponible del producto."; return }
        adding = true
        scope.launch {
            try {
                val entries = if (demo) {
                    DemoInventory.products.first { it.code == selected.product.code }.units
                        .filter { it !in store.usedCodes() }.map { StockEntry(it, selected.product.id, it, 1) }
                } else if (selected.entry != null) listOf(selected.entry) else {
                    val found = mutableListOf<StockEntry>()
                    var cursor: com.google.firebase.firestore.DocumentSnapshot? = null
                    var free = 0L
                    do {
                        val page = remote.availableEntries(selected.product.id, cursor, amount - free)
                        found += page.entries
                        cursor = page.cursor
                        free = found.sumOf { entry ->
                            (entry.quantity - cart.filter { it.entryId == entry.id }.sumOf { it.quantity }).coerceAtLeast(0)
                        }
                        if (free >= amount || !page.hasMore) break
                    } while (true)
                    found
                }
                cart = ArrayList(addPendingExit(cart, selected.product, entries, amount))
                input = ""; quantity = ""; match = null; lookupError = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = inventoryError(e) }
            finally { adding = false }
        }
    }
    val onSave: () -> Unit = {
        val error = when {
            !ready -> "Espera a que se carguen CLISA, TOQEN y los receptores."
            input.isNotBlank() || match != null -> "Agrega el producto actual a la lista antes de guardar."
            cart.isEmpty() -> "Agrega al menos un producto a la lista."
            !demo && liveReceiver == null -> "Selecciona el usuario que retira."
            demo && receiverName.isBlank() -> "Selecciona el usuario que retira."
            payment !in listOf("Pagado", "Pendiente") -> "Selecciona el estado de pago."
            submitted && !demo && liveReceiver?.name != submittedName -> "El receptor cambió. Revisa las salidas pendientes antes de continuar."
            else -> null
        }
        if (error != null) message = error else {
            submitted = true
            submittedName = if (demo) receiverName else liveReceiver!!.name
            val recipient = liveReceiver
            val savePayment = payment
            val pending = cart.toList()
            saving = true
            scope.launch {
                try {
                    for (line in pending) {
                        if (demo) {
                            val item = DemoInventory.products.first { it.code == line.productCode }
                            val result = withContext(Dispatchers.IO) {
                                store.save(item, listOf(line.entryCode), line.quantity.toInt(),
                                    DemoInventory.receivers.first { it.name == receiverName }, savePayment)
                            }
                            check(result == null) { result.orEmpty() }
                        } else remote.save(line.requestId, line.product(), line.entry(), line.quantity, recipient!!, savePayment)
                        cart = ArrayList(cart.filter { it.requestId != line.requestId })
                        completed++
                    }
                    success = "$completed salidas confirmadas.\nCLISA → $destination\nPago: $savePayment"
                    submitted = false; completed = 0; receiverId = ""; receiverName = ""; payment = ""
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    message = "${inventoryError(e)}\n$completed salidas confirmadas; ${cart.size} pendientes. Reintentar conserva los identificadores para evitar duplicados."
                } finally { saving = false }
            }
        }
    }
    Surface(Modifier.fillMaxSize(), color = PageBackground) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth()) {
                RegistrationBanner(Modifier.matchParentSize())
                Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { leave() }, enabled = !busy, modifier = Modifier.size(56.dp)) {
                    RegisterIcon("back", Navy, "Volver")
                }
                    Text("Registrar y escanear", color = Navy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text("Registra salidas de artículos informáticos", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp, end = 66.dp, top = 4.dp, bottom = 10.dp))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (!demo && !ready) {
                    Text(live.error ?: if (live.loading) "Conectando con Firebase y cargando tiendas y receptores…" else "CLISA y TOQEN deben existir y estar activas en Firestore.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { live = InventoryData(); reload++ }, enabled = !live.loading && !busy) { Text("Reintentar conexión") }
                }
                FormRow("calendar", iconColor = ActionBlue) {
                    Label("Fecha de registro")
                    ReadOnlyValue(ZonedDateTime.now(ZoneId.of("America/Lima")).format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-PE"))), emphasized = true)
                }
                FormCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormRow("barcode", framed = false) {
                        Label("Código del producto")
                        CompactField(input, { input = it; match = null; lookupError = null }, "Ingresa o escanea el código", enabled = !busy && !submitted, onDone = { lookup(input) })
                        lookupError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scanning = true
                            GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().enableAutoZoom().build()).startScan()
                                .addOnSuccessListener { result -> scanning = false; result.rawValue?.let { lookup(it) } ?: run { message = "No se pudo leer el código. Inténtalo nuevamente." } }
                                .addOnCanceledListener { scanning = false }
                                .addOnFailureListener { scanning = false; message = "No se pudo abrir el escáner. Revisa Google Play Services y tu conexión o ingresa el código manualmente." }
                        }, enabled = ready && !busy && !submitted, modifier = Modifier.weight(1f).heightIn(min = 46.dp), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = ActionBlue, contentColor = Color.White)) {
                            RegisterIcon("scan", Color.White, null, 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (scanning) "Abriendo…" else "Escanear código", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(onClick = { lookup(input) }, enabled = ready && !busy && !submitted, modifier = Modifier.weight(1f).heightIn(min = 46.dp), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), border = BorderStroke(1.dp, FieldBorder), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF052045), contentColor = Color.White)) {
                            RegisterIcon("search", Color.White, null, 24.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Buscar producto", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                }
                FormRow("product") {
                    Label("Producto")
                    ReadOnlyValue(if (loadingProduct) "Buscando…" else product?.name ?: "Nombre del producto")
                    if (product != null) Text("Stock disponible: $maxQuantity unidades", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                FormCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormRow("quantity", framed = false) {
                    Label("Cantidad")
                    CompactField(quantity, { setQuantity(it) }, "Ingresa la cantidad", enabled = product != null && !busy && !submitted, numeric = true)
                    if ((quantity.toLongOrNull() ?: 0) > maxQuantity) Text("La cantidad supera el stock disponible", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.fillMaxWidth().background(Color(0xFF03192F), RoundedCornerShape(10.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Productos para registrar", color = Navy, style = MaterialTheme.typography.bodyMedium)
                    cart.groupBy { it.productId }.forEach { (_, lines) ->
                        val item = lines.first()
                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF07346B), border = BorderStroke(1.dp, FieldBorder)) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.productName, color = Navy, style = MaterialTheme.typography.bodyMedium)
                                    Text("${item.productCode} · ${lines.sumOf { it.quantity }} unidades", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { cart = ArrayList(cart.filter { it.productId != item.productId }) }, enabled = !busy && !submitted) {
                                    RegisterIcon("close", Color.White, "Eliminar ${item.productName}", 18.dp)
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { addProduct() }, enabled = !busy && ready && !submitted && product != null,
                        shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, FieldBorder), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.heightIn(min = 36.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF009EFF))) {
                        Text(if (adding) "Agregando…" else "+ Agregar", style = MaterialTheme.typography.labelMedium)
                    }
                    if (submitted) Text("$completed salidas confirmadas. Reintenta para guardar las pendientes.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                }
                }
                FormRow("user") {
                    Selection("Usuario que retira", receiverDisplay, receiverOptions, !busy && !submitted && ready && receiverOptions.isNotEmpty(), "Selecciona un receptor") { label ->
                        receiverName = label
                        if (!demo) receiverId = live.receivers[receiverOptions.indexOf(label)].id
                    }
                    if (!demo && ready && receiverOptions.isEmpty()) Text("No hay receptores activos para TOQEN.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                FormRow("store") {
                    Label("Tienda de destino")
                    ReadOnlyValue(destination)
                }
                FormRow("payment") {
                    Selection("Estado de pago", payment, listOf("Pagado", "Pendiente"), !busy && !submitted && ready, "Selecciona el estado de pago") { payment = it }
                }
            }
            Button(onClick = onSave, enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 8.dp).heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = ActionBlue, contentColor = Color.White), contentPadding = PaddingValues(14.dp)) {
                RegisterIcon("save", Color.White, null, 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (saving) "Guardando…" else "Guardar salida", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    message?.let { error -> AlertDialog(onDismissRequest = { message = null }, title = { Text("Revisa la salida") }, text = { Text(error) }, confirmButton = { TextButton(onClick = { message = null }) { Text("Entendido") } }) }
    success?.let { details -> AlertDialog(onDismissRequest = { success = null }, title = { Text("Salida guardada") }, text = { Text("$details\n\n" + if (demo) "Guardada en este dispositivo (demostración)." else "Confirmada en Firestore. Stock actualizado.") }, confirmButton = { TextButton(onClick = { success = null; onBack() }) { Text("Volver al panel") } }, dismissButton = { TextButton(onClick = { success = null }) { Text("Nueva salida") } }) }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("¿Salir sin guardar?") }, text = { Text(if (submitted) "Las salidas confirmadas se conservan. Se perderá la lista de pendientes; revisa el historial antes de volver a registrarlas." else "Se perderán los productos pendientes de esta salida.") }, confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("Salir") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Continuar") } })
}


@Composable
private fun Label(value: String) {
    Text(value, color = Navy, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun FormCard(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color.Transparent, border = BorderStroke(1.dp, Color(0xFF07568B))) {
        Box(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF031C34), Color(0xFF02152B)))).padding(6.dp)) { content() }
    }
}

@Composable
private fun FormRow(icon: String, iconColor: Color = Navy, framed: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(44.dp).background(Color(0xFF001127), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                RegisterIcon(icon, iconColor, null, 28.dp)
            }
            Column(Modifier.weight(1f), content = content)
        }
    }
    if (framed) FormCard(row) else row()
}

@Composable
private fun CompactField(value: String, onChange: (String) -> Unit, placeholder: String,
    enabled: Boolean = true, numeric: Boolean = false, modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    BasicTextField(value, onChange, enabled = enabled, singleLine = true,
        modifier = modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium.copy(color = Navy),
        cursorBrush = SolidColor(ActionBlue),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).border(1.dp, FieldBorder, RoundedCornerShape(9.dp)).background(Brush.horizontalGradient(listOf(InputBackground, Color(0xFF082B50))), RoundedCornerShape(9.dp)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (value.isEmpty()) Text(placeholder, color = Color(0xFF709BC7), style = MaterialTheme.typography.bodyMedium)
                    inner()
                }
                trailing?.invoke(this)
            }
        })
}

@Composable
private fun ReadOnlyValue(value: String, chevron: Boolean = false, onClick: (() -> Unit)? = null, description: String? = null, emphasized: Boolean = false) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = if (emphasized) Navy else Muted, fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (chevron) RegisterIcon("down", Muted, null, 16.dp)
        }
    }
    val modifier = Modifier.fillMaxWidth().then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })
    if (onClick != null) Surface(onClick, modifier, shape = RoundedCornerShape(9.dp), color = InputBackground, border = BorderStroke(1.dp, FieldBorder), content = content)
    else Surface(modifier, shape = RoundedCornerShape(9.dp), color = InputBackground, border = BorderStroke(1.dp, FieldBorder), content = content)
}

@Composable
private fun Selection(label: String, value: String, options: List<String>, enabled: Boolean, placeholder: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Label(label)
        Box {
            Surface(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp), color = InputBackground, border = BorderStroke(1.dp, FieldBorder)) {
                Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value.ifEmpty { placeholder }, style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(1f))
                    RegisterIcon("down", Muted, null, 16.dp)
                }
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
            }
        }
    }
}

@Composable
internal fun RegisterIcon(kind: String, color: Color, description: String?, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        val s = this.size.width / 24f
        val stroke = Stroke(1.8f * s)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x*s,y*s), Offset(xx*s,yy*s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun rect(x: Float,y: Float,w: Float,h: Float) = drawRoundRect(color, Offset(x*s,y*s), Size(w*s,h*s), CornerRadius(1.5f*s), style = stroke)
        fun circle(x: Float,y: Float,r: Float) = drawCircle(color, r*s, Offset(x*s,y*s), style = stroke)
        when (kind) {
            "back" -> { line(19f,12f,5f,12f); line(5f,12f,11f,6f); line(5f,12f,11f,18f) }
            "calendar" -> { rect(4f,5f,16f,16f); line(8f,2f,8f,8f); line(16f,2f,16f,8f); line(4f,10f,20f,10f); line(9f,15f,11f,17f); line(11f,17f,15f,13f) }
            "barcode" -> { for (x in listOf(4f,7f,9f,13f,16f,20f)) line(x,5f,x,19f); line(2f,3f,6f,3f); line(18f,3f,22f,3f); line(11f,7f,11f,17f); line(18f,8f,18f,21f) }
            "search" -> { circle(10f,10f,6f); line(15f,15f,21f,21f) }
            "scan" -> { line(3f,8f,3f,3f); line(3f,3f,8f,3f); line(16f,3f,21f,3f); line(21f,3f,21f,8f); line(3f,16f,3f,21f); line(3f,21f,8f,21f); line(16f,21f,21f,21f); line(21f,21f,21f,16f); line(8f,12f,16f,12f) }
            "product" -> { drawPath(Path().apply { moveTo(12*s,2*s); lineTo(21*s,7*s); lineTo(21*s,17*s); lineTo(12*s,22*s); lineTo(3*s,17*s); lineTo(3*s,7*s); close() },color,style=stroke); line(3f,7f,12f,12f); line(12f,12f,21f,7f); line(12f,12f,12f,22f) }
            "user" -> { circle(12f,6f,3.5f); drawArc(color,180f,180f,false,Offset(5*s,12*s),Size(14*s,14*s),style=stroke); line(5f,19f,19f,19f) }
            "quantity" -> {
                fun cube(x: Float, y: Float) {
                    drawPath(Path().apply { moveTo((x+4)*s,y*s); lineTo((x+8)*s,(y+2.5f)*s); lineTo((x+8)*s,(y+8)*s); lineTo((x+4)*s,(y+10.5f)*s); lineTo(x*s,(y+8)*s); lineTo(x*s,(y+2.5f)*s); close() },color,style=stroke)
                    line(x,y+2.5f,x+4,y+5); line(x+4,y+5,x+8,y+2.5f); line(x+4,y+5,x+4,y+10.5f)
                }
                cube(8f,1f); cube(3f,11f); cube(13f,11f)
            }
            "store" -> {
                rect(4f,10f,16f,12f); rect(10f,15f,5f,7f)
                drawPath(Path().apply { moveTo(2*s,9*s); lineTo(5*s,3*s); lineTo(19*s,3*s); lineTo(22*s,9*s); cubicTo(22*s,13*s,18*s,13*s,17*s,10*s); cubicTo(16*s,13*s,12*s,13*s,12*s,10*s); cubicTo(11*s,13*s,7*s,13*s,7*s,10*s); cubicTo(6*s,13*s,2*s,13*s,2*s,9*s) },color,style=stroke)
            }
            "payment" -> { rect(2f,4f,20f,16f); line(3f,9f,21f,9f); line(6f,16f,10f,16f) }
            "save" -> { rect(3f,3f,18f,18f); rect(7f,3f,9f,6f); rect(7f,13f,10f,8f); line(13f,4f,13f,7f) }
            "right" -> { line(9f,6f,15f,12f); line(15f,12f,9f,18f) }
            "down" -> { line(6f,9f,12f,15f); line(12f,15f,18f,9f) }
            "stepper" -> { line(7f,8f,12f,3f); line(12f,3f,17f,8f); line(7f,16f,12f,21f); line(12f,21f,17f,16f) }
            "close" -> { line(6f,6f,18f,18f); line(6f,18f,18f,6f) }
        }
    }
}

@Composable
internal fun RegistrationBanner(modifier: Modifier, showScanner: Boolean = true) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.horizontalGradient(listOf(PageBackground,Color(0xFF031D49))))
        val sweep = Path().apply { moveTo(0f,0f); lineTo(w*.85f,0f); lineTo(w*.63f,h*.55f); quadraticTo(w*.58f,h*.65f,w*.47f,h*.65f); lineTo(0f,h*.65f); close() }
        drawPath(sweep,Color(0xFF04142E))
        for (i in 0..4) {
            val x = w * (.64f+i*.055f)
            val bottom = h*.95f-i*h*.06f
            val barHeight = h*(.16f+i*.055f)
            val barWidth = w*.036f
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0088FF).copy(alpha=.6f), Color(0xFF003487).copy(alpha=.12f))),Offset(x,bottom-barHeight),Size(barWidth,barHeight))
            drawPath(Path().apply { moveTo(x,bottom-barHeight); lineTo(x+barWidth*.5f,bottom-barHeight-barWidth*.3f); lineTo(x+barWidth*1.5f,bottom-barHeight); lineTo(x+barWidth,bottom-barHeight+barWidth*.3f); close() },Color(0xFF006FFF).copy(alpha=.45f))
        }
        if (showScanner) {
        val s = w*.12f/24f
        val x = w*.86f
        val y = h*.50f
        fun line(a: Float,b: Float,c: Float,d: Float) = drawLine(Color(0xFF008CFF),Offset(x+a*s,y+b*s),Offset(x+c*s,y+d*s),1.5f*s,StrokeCap.Round)
        line(2f,7f,2f,2f); line(2f,2f,7f,2f); line(17f,2f,22f,2f); line(22f,2f,22f,7f)
        line(2f,17f,2f,22f); line(2f,22f,7f,22f); line(17f,22f,22f,22f); line(22f,22f,22f,17f)
        for (a in listOf(7f,10f,12f,15f,17f)) line(a,8f,a,16f)
        }
    }
}

