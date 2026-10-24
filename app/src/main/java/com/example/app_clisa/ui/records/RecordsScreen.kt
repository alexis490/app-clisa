package com.example.app_clisa.ui.records

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.app_clisa.ui.registration.RegisterIcon
import com.example.app_clisa.ui.registration.RegistrationBanner
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.FirebaseFirestoreException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.app_clisa.data.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.testTag

private val White = Color(0xFFF1F5FF)
private val Muted = Color(0xFFABC7EC)
private val Blue = Color(0xFF0061FF)
private val Background = Color(0xFF020E21)
private val Border = Color(0xFF07568B)
private val locale = Locale.forLanguageTag("es-PE")
private val zone = ZoneId.of("America/Lima")

internal data class ExitRecord(
    val id: String, val product: String, val code: String, val quantity: Long,
    val registeredBy: String, val receiver: String, val destination: String,
    val unitCode: String, val time: Instant?, val payment: String?, val observations: String?,
    val formalization: FormalizationState = FormalizationState.UNVERIFIED, val confirmable: Boolean = false,
) {
    val day: String get() = time?.atZone(zone)?.toLocalDate()?.toString().orEmpty()
    fun date(pattern: String): String = time?.atZone(zone)?.format(DateTimeFormatter.ofPattern(pattern, locale)) ?: "Fecha no disponible"
    fun matches(query: String): Boolean = listOf(product, code, unitCode, registeredBy, receiver, destination).any { it.contains(query.trim(), true) }
}

@Composable
fun RecordsScreen(onBack: () -> Unit, authenticated: Boolean, role: String? = null) {
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
    var records by remember { mutableStateOf(emptyList<ExitRecord>()) }
    var confirmed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var product by rememberSaveable { mutableStateOf("") }
    var receiver by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var confirmation by remember { mutableStateOf<ExitRecord?>(null) }
    var formalizing by remember { mutableStateOf(false) }
    var formalizationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(authenticated, role) { if (!authenticated || !TransferFormalization.authorized(role)) confirmation = null }
    LaunchedEffect(records, confirmation?.id, formalizing) {
        val id = confirmation?.id
        if (!formalizing && id != null && records.find { it.id == id }?.formalization == FormalizationState.COMPLETED) confirmation = null
    }
    fun confirmFormalization() {
        val target = confirmation ?: return
        if (formalizing || !TransferFormalization.authorized(role) || !target.confirmable) return
        val actor = FirebaseAuth.getInstance().currentUser?.uid ?: return
        formalizing = true; formalizationError = null
        scope.launch {
            try {
                FirestoreTransferFormalization().confirm(target.id)
                check(FirebaseAuth.getInstance().currentUser?.uid == actor) { "La sesión cambió. Vuelve a abrir el historial." }
                // Only a server-acknowledged transaction (or already-completed server read) changes the badge.
                records = records.map { if (it.id == target.id) it.copy(formalization = FormalizationState.COMPLETED, confirmable = false) else it }
                confirmation = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                formalizationError = when {
                    e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "Tu cuenta no tiene permiso para confirmar este traspaso o la función aún no está habilitada."
                    e is FirebaseFirestoreException -> "No se pudo confirmar el resultado. Revisa tu conexión antes de reintentar."
                    else -> e.message ?: "No se pudo completar la confirmación."
                }
            } finally { formalizing = false }
        }
    }
    DisposableEffect(authenticated, retry) {
        confirmed = false
        error = null
        val listener = if (authenticated) FirebaseFirestore.getInstance().collection("registros")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) {
                    error = if ((failure as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                        "Tu cuenta no tiene permiso para consultar los registros." else "No se pudo actualizar el historial. Revisa tu conexión."
                } else if (snapshot != null) {
                    confirmed = !snapshot.metadata.isFromCache
                    records = snapshot.documents.filter {
                        it.getString("tipo") == "SALIDA" && it.getString("estado") == "COMPLETADO"
                    }.mapNotNull { doc ->
                        if (doc.metadata.hasPendingWrites()) return@mapNotNull records.find { it.id == doc.id }
                        ExitRecord(doc.id, doc.getString("nombreProducto") ?: "Producto sin nombre", doc.getString("codigoProducto").orEmpty(),
                            doc.getLong("cantidad") ?: 0, doc.getString("registradoPorNombre") ?: "No registrado",
                            doc.getString("recibidoPor") ?: "No registrado", doc.getString("tiendaDestinoNombre") ?: "No registrada",
                            doc.getString("codigoExistencia").orEmpty(), doc.getTimestamp("fechaHora")?.toDate()?.toInstant(),
                            doc.getString("estadoPago"), doc.getString("observaciones"),
                            TransferFormalization.state(doc.data.orEmpty()), TransferFormalization.confirmable(doc.data.orEmpty()))
                    }.sortedWith(compareByDescending<ExitRecord> { it.time ?: Instant.MIN }.thenBy { it.id })
                    error = null
                }
            } else null
        onDispose { listener?.remove() }
    }
    fun leave() { if (selectedId != null) selectedId = null else onBack() }
    BackHandler { leave() }
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, onPrimary = White, surface = Color(0xFF031A34), onSurface = White,
        onSurfaceVariant = Muted, outline = Border, secondary = Muted)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Box(Modifier.fillMaxWidth()) {
                    RegistrationBanner(Modifier.matchParentSize(), showScanner = false)
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = if (selectedId == null) 62.dp else 56.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { leave() }, modifier = Modifier.size(if (selectedId == null) 56.dp else 48.dp)) { RegisterIcon("back", White, "Volver") }
                            Text(if (selectedId == null) "Lista de registros" else "Detalle de registro", color = White,
                                style = if (selectedId == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                        }
                        if (selectedId == null) {
                            Text("Historial de salidas registradas", color = Muted, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 16.dp, end = 66.dp, top = 4.dp, bottom = 10.dp))
                        }
                    }
                }
                if (!authenticated) {
                    EmptyMessage("Inicia sesión para consultar tus registros reales.")
                } else {
                    error?.let { message ->
                        Text(message, color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
                        TextButton(onClick = { retry++ }) { Text("Reintentar") }
                    }
                    if (!confirmed && error == null) Text(if (records.isEmpty()) "Conectando con Firestore…" else "Datos guardados en caché. Esperando actualización…", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    if (selectedId != null) {
                        val record = records.find { it.id == selectedId }
                        if (record != null) RecordDetail(record)
                        else EmptyMessage(if (confirmed) "El registro no está disponible." else "Cargando registro…")
                    } else {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("Buscar por código, producto o usuario…", style = MaterialTheme.typography.bodySmall) },
                            leadingIcon = { RegisterIcon("search", Muted, null) }, singleLine = true, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), textStyle = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RecordFilter("Fecha", "calendar", date, records.map { it.day }.filter { it.isNotBlank() }.distinct().sortedDescending(), Modifier.weight(1f)) { date = it }
                            RecordFilter("Producto", "product", product, records.map { it.product }.distinct().sorted(), Modifier.weight(1f)) { product = it }
                            RecordFilter("Receptor", "user", receiver, records.map { it.receiver }.distinct().sorted(), Modifier.weight(1f)) { receiver = it }
                        }
                        val filtered = records.filter { it.matches(query) && (date.isEmpty() || it.day == date) && (product.isEmpty() || it.product == product) && (receiver.isEmpty() || it.receiver == receiver) }
                        if (filtered.isEmpty() && confirmed && error == null) EmptyMessage(if (records.isEmpty()) "Aún no hay salidas registradas.\nCuando guardes una salida, aparecerá aquí." else "No hay registros que coincidan con la búsqueda o los filtros.")
                        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered, key = { it.id }) { record -> RecordCard(record,
                                canConfirm = TransferFormalization.authorized(role) && record.confirmable && !formalizing,
                                onFormalize = { confirmation = record; formalizationError = null }, onClick = { selectedId = record.id }) }
                        }
                    }
                }
            }
            confirmation?.let { target -> FormalizationDialog(target.product, target.code, formalizing, formalizationError,
                onCancel = { if (!formalizing) { confirmation = null; formalizationError = null } }, onConfirm = ::confirmFormalization) }
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecordFilter(label: String, icon: String, value: String, options: List<String>, modifier: Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (value.isEmpty()) Border else Blue),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = White)) {
            RegisterIcon(icon, if (value.isEmpty()) Muted else Color(0xFF009EFF), null, 18.dp)
            Spacer(Modifier.width(5.dp))
            Text(label + if (value.isNotEmpty()) " •" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            RegisterIcon("down", White, null, 12.dp)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Todos") }, onClick = { onSelect(""); expanded = false })
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}

@Composable
internal fun RecordCard(record: ExitRecord, canConfirm: Boolean, onFormalize: () -> Unit, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.testTag("record-${record.id}"), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), color = Color.Transparent) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF04203B), Color(0xFF02132B)))).padding(12.dp)) {
            val compact = maxWidth < 320.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (compact) Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) {
                    FormalizationBadge(record.formalization, canConfirm, record.id, onFormalize)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProductPlaceholder()
                    Column(Modifier.weight(1f)) {
                        Text(record.product, color = White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Código: ${record.code}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!compact) Column(Modifier.align(Alignment.Top), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FormalizationBadge(record.formalization, canConfirm, record.id, onFormalize)
                        Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuantityBadge("${record.quantity} un.")
                            RegisterIcon("right", Muted, null, 16.dp)
                        }
                    }
                }
                if (compact) Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End,verticalAlignment = Alignment.CenterVertically) {
                    QuantityBadge("${record.quantity} un.")
                    Spacer(Modifier.width(8.dp)); RegisterIcon("right", Muted, null, 16.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine("calendar", record.date("d MMM yyyy  HH:mm"))
                        InfoLine("user", "Registrado por: ${record.registeredBy}")
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine("user", "Receptor: ${record.receiver}")
                        InfoLine("store", "Tienda: ${record.destination}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductPlaceholder() {
    Box(Modifier.size(56.dp).background(Color(0xFF0B315B), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        RegisterIcon("product", White, null, 32.dp)
    }
}

@Composable
private fun QuantityBadge(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF0050C9)) { Text(text, color = White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
}

@Composable
private fun InfoLine(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RegisterIcon(icon, White, null, 20.dp)
        Text(text, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFF031A34), border = BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordDetail(record: ExitRecord) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProductPlaceholder()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.product, color = White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    QuantityBadge("${record.quantity} unidades")
                    Text("Código: ${record.code}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        DetailCard {
            DetailRow("calendar", "Fecha y hora", record.date("d 'de' MMMM 'de' yyyy  HH:mm"))
            DetailRow("user", "Registrado por", record.registeredBy)
            DetailRow("user", "Receptor (usuario)", record.receiver)
            DetailRow("store", "Tienda de destino", record.destination)
            DetailRow("payment", "Estado de pago", record.payment ?: "No registrado")
        }
        DetailCard { DetailRow("product", "Cantidad total", "${record.quantity} unidades", divider = false) }
        DetailCard {
            Text("Códigos de los productos", color = Muted, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record.unitCode.isNotBlank()) QuantityBadge(record.unitCode) else Text("No registrados", color = Muted)
            }
        }
        DetailCard {
            InfoLine("barcode", "Información adicional")
            Text("Observaciones", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(record.observations?.takeIf { it.isNotBlank() } ?: "Sin observaciones registradas.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DetailRow(icon: String, label: String, value: String, divider: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RegisterIcon(icon, White, null, 24.dp)
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, color = White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.3f))
    }
    if (divider) HorizontalDivider(color = Border.copy(alpha = .35f))
}
