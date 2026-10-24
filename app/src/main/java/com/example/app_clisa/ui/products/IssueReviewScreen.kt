package com.example.app_clisa.ui.products

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.app_clisa.data.IssueReviewAuthorization
import com.example.app_clisa.data.excel.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun IssueReviewEntry(catalog: IssueReviewCatalog?, journal: IssueReviewJournal?, onOpen: () -> Unit) {
    if (catalog == null) return
    val counts = catalog.counts(journal ?: IssueReviewJournal(catalog.sourceSha256))
    ReviewCard {
        Text("Incidencias del Excel · ${counts.total}", style = MaterialTheme.typography.titleSmall)
        if (journal == null) Text("Abre el expediente para recuperar sus estados guardados en este dispositivo.")
        else Text("${counts.pending} pendientes · ${counts.reviewed} revisadas · ${counts.resolved} resueltas · ${counts.blocked} bloqueadas")
        Text("Dry Run: ${if (journal == null) "estados por recuperar" else "${counts.inReview} en revisión"}; 0 correcciones aplicadas. Las decisiones no eliminan bloqueos ni verifican el corte temporal.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpen, modifier = Modifier.testTag("open-issue-review")) { Text("Revisar incidencias") }
    }
}

@Composable
internal fun IssueReviewHost(catalog: IssueReviewCatalog, onJournal: (IssueReviewJournal) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val store = remember(uid) { uid?.let { IssueReviewStore(File(context.noBackupFilesDir, "excel-issue-reviews"), it) } }
    var journal by remember(catalog.sourceSha256, uid) { mutableStateOf(IssueReviewJournal(catalog.sourceSha256)) }
    var editable by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(catalog.sourceSha256, uid) {
        try {
            journal = withContext(Dispatchers.IO) { requireNotNull(store) { "Inicia sesión para recuperar el expediente local." }.load(catalog.sourceSha256) }
            onJournal(journal); loaded = true
            editable = try { IssueReviewAuthorization.verify().uid == uid } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "No se pudo abrir el expediente. Se conserva sin sobrescribir; requiere revisión local." }
    }
    if (!loaded) {
        ImportFlowFrame("Revisar incidencias", "Recuperando expediente local del Excel", 3, onBack) {
            Text(error ?: "Abriendo expediente…", modifier = Modifier.padding(16.dp))
        }
        return
    }
    IssueReviewScreen(catalog, journal, editable && loaded, busy, error, onBack) { item, proposed, reason, evidence, state ->
        busy = true; error = null
        scope.launch {
            try {
                val reviewer = IssueReviewAuthorization.verify()
                check(reviewer.uid == uid) { "La sesión cambió. Abre de nuevo la revisión." }
                val decision = IssueDecision(item.id, item.issue.kind, catalog.original(item), proposed, reason, evidence,
                    reviewer.uid, System.currentTimeMillis(), state, catalog.sourceSha256)
                val revision = journal.revision
                journal = withContext(Dispatchers.IO) {
                    requireNotNull(store).save(catalog, decision, reviewer.uid, reviewer.role, revision)
                }
                onJournal(journal)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is IllegalArgumentException || e is IllegalStateException) e.message else "No se pudo verificar el acceso o guardar el expediente. No se aplicaron correcciones." }
            finally { busy = false }
        }
    }
}

@Composable
internal fun IssueReviewScreen(catalog: IssueReviewCatalog, journal: IssueReviewJournal, canEdit: Boolean,
    busy: Boolean = false, error: String? = null, onBack: () -> Unit,
    onSave: (IssueReviewItem, String, String, String, IssueReviewState) -> Unit) {
    var kind by remember { mutableStateOf<ExcelIssueKind?>(null) }
    var state by remember { mutableStateOf<IssueReviewState?>(null) }
    var query by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<IssueReviewItem?>(null) }
    var showCopy by remember { mutableStateOf(false) }
    LaunchedEffect(query) { delay(350); search = query }
    LaunchedEffect(kind, state, search) { page = 0 }
    val counts = remember(journal, catalog) { catalog.counts(journal) }
    val filtered = remember(catalog, journal, kind, state, search) { catalog.filter(journal, kind, state, search) }
    val pages = maxOf(1, (filtered.size + 24) / 25)
    ImportFlowFrame(if (selected == null) "Revisar incidencias" else "Detalle de incidencia",
        "Expediente local · Solo propuestas, sin cambios en Excel ni inventario", 3,
        { if (selected != null) selected = null else onBack() }) {
        if (selected != null) {
            IssueEditor(catalog, selected!!, journal, canEdit, busy, error, onSave)
        } else {
            LazyColumn(Modifier.weight(1f).testTag("issue-review-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { ReviewCard {
                    Text("Total ${counts.total} · Pendientes ${counts.pending}")
                    Text("Revisadas ${counts.reviewed} · Resueltas ${counts.resolved} · Bloqueadas ${counts.blocked}")
                    Text("En revisión ${counts.inReview} · Aplicadas a copia 0", style = MaterialTheme.typography.bodySmall)
                    Text("SHA-256: ${catalog.sourceSha256}", style = MaterialTheme.typography.labelSmall)
                    if (!canEdit) Text("Solo lectura. Para proponer se requiere una cuenta ADMIN o SUPER_ADMIN activa y verificada.")
                    if (error != null) Text(error, color = Color(0xFFFFBC39))
                } }
                item { ReviewCard {
                    val grouped = catalog.items.groupingBy { it.issue.kind }.eachCount()
                    ReviewMenu("Tipo", kind?.name ?: "Todos", listOf(null) + grouped.keys.toList(), { it?.let { k -> "${k.name} (${grouped[k]})" } ?: "Todos" }) { kind = it }
                    ReviewMenu("Estado", state?.name ?: "Todos", listOf(null) + IssueReviewState.entries, { it?.name ?: "Todos" }) { state = it }
                    OutlinedTextField(query, { query = it.take(200) }, label = { Text("Producto, SKU, serie o fila") }, modifier = Modifier.fillMaxWidth().testTag("issue-search"))
                } }
                items(filtered.drop(page.coerceAtMost(pages - 1) * 25).take(25), key = { it.id }) { item ->
                    Surface(onClick = { selected = item }, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF07568B)), color = Color(0xFF03152C)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${item.issue.kind} · ${journal.latest[item.id]?.state ?: IssueReviewState.PENDIENTE}", color = Color(0xFF67B8FF))
                            Text(item.issue.productCode ?: item.issue.productName ?: "Identidad desconocida")
                            Text("${item.issue.sheet} · fila ${item.issue.row ?: "?"} · serie «${item.issue.serialCode.orEmpty()}»")
                            Text(item.issue.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { ReviewPages(page, pages, filtered.size) { page = it } }
                item { ReviewCard {
                    Text("Dry Run: decisiones documentadas ≠ correcciones aplicadas. El archivo conserva sus incidencias y las validaciones temporales independientes.")
                    OutlinedButton(onClick = { showCopy = true }) { Text("Revisar propuesta de copia") }
                    Button(onClick = {}, enabled = false) { Text("Sincronización no disponible") }
                } }
            }
        }
    }
    if (showCopy) {
        val prepared = runCatching { catalog.prepareCopy(journal) }
        AlertDialog(onDismissRequest = { showCopy = false }, title = { Text("Resumen antes de generar") },
            text = { Text(prepared.fold({ "${it.approved.size} decisiones resueltas con evidencia.\n${it.blockedReason}\nNo se generó ni modificó ningún archivo." },
                { "Las decisiones requieren revalidación: ${it.message}. No se generó ningún archivo." })) },
            confirmButton = { TextButton(onClick = { showCopy = false }) { Text("Entendido") } })
    }
}

@Composable
private fun IssueEditor(catalog: IssueReviewCatalog, item: IssueReviewItem, journal: IssueReviewJournal,
    canEdit: Boolean, busy: Boolean, error: String?, onSave: (IssueReviewItem, String, String, String, IssueReviewState) -> Unit) {
    val latest = journal.latest[item.id]
    var proposed by remember(item.id, latest) { mutableStateOf(latest?.proposed ?: "") }
    var reason by remember(item.id, latest) { mutableStateOf(latest?.reason ?: "") }
    var evidence by remember(item.id, latest) { mutableStateOf(latest?.evidence ?: "") }
    var state by remember(item.id, latest) { mutableStateOf(latest?.state ?: IssueReviewState.EN_REVISION) }
    var confirm by remember { mutableStateOf(false) }
    var page by remember(item.id) { mutableIntStateOf(0) }
    val rows = remember(catalog, item) { catalog.occurrences(item) }
    val events = remember(item, journal) { journal.events.filter { it.issueId == item.id }.asReversed() }
    var auditPage by remember(item.id) { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize().testTag("issue-editor"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ReviewCard {
            Text(item.issue.kind.name, style = MaterialTheme.typography.titleSmall)
            Text("ID: ${item.id}", style = MaterialTheme.typography.labelSmall)
            Text("${item.issue.sheet} · fila ${item.issue.row}")
            Text(item.issue.message)
            Text("Original: \"${catalog.original(item)}\"")
            if (catalog.suggestion(item).isNotEmpty()) Text("Propuesta disponible: \"${catalog.suggestion(item)}\". Debe ingresarse y confirmarse expresamente.")
            Text("SKU faltante/serie normalizada: exige comparación completa sin colisiones. Duplicados: revisa TODAS las apariciones y documenta REPETICION_LEGITIMA o CORREGIR_EN_ORIGEN. Cantidad negativa: entero no negativo con evidencia del sistema principal.", style = MaterialTheme.typography.bodySmall)
        } }
        items(rows.drop(page * 25).take(25)) { row -> ReviewCard {
            Text("${row.sheet} · fila ${row.row} · SKU ${row.productCode ?: "ausente"}")
            Text(row.name); Text("Serie: \"${row.serial.orEmpty()}\" · Cantidad: ${row.quantity ?: "desconocida"}")
            row.documentId?.let { Text("ID real Firestore: $it") }
        } }
        item { ReviewPages(page, maxOf(1, (rows.size + 24) / 25), rows.size) { page = it } }
        item { ReviewCard {
            ReviewMenu("Estado propuesto", state.name, IssueReviewState.entries, { it.name }) { state = it }
            OutlinedTextField(proposed, { proposed = it.take(4000) }, enabled = canEdit && !busy, label = { Text("Valor propuesto / decisión") }, modifier = Modifier.fillMaxWidth().testTag("issue-proposal"))
            OutlinedTextField(reason, { reason = it.take(4000) }, enabled = canEdit && !busy, label = { Text("Justificación") }, modifier = Modifier.fillMaxWidth().testTag("issue-reason"))
            OutlinedTextField(evidence, { evidence = it.take(4000) }, enabled = canEdit && !busy, label = { Text("Evidencia o referencia documental") }, modifier = Modifier.fillMaxWidth().testTag("issue-evidence"))
            Text("RESUELTO documenta una decisión. No corrige el archivo, no valida automáticamente la evidencia externa ni habilita sincronización.")
            if (error != null) Text(error, color = Color(0xFFFFBC39))
            Button(onClick = { confirm = true }, enabled = canEdit && !busy, modifier = Modifier.testTag("issue-save")) { Text(if (busy) "Verificando acceso…" else "Guardar decisión local") }
        } }
        item { Text("Historial de revisión · ${events.size} eventos") }
        items(events.drop(auditPage * 25).take(25)) { d -> ReviewCard {
            Text("${d.state} · ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(Date(d.atMillis))}")
            Text("Responsable: ${d.reviewer}\nOriginal: \"${d.original}\"\nPropuesto: \"${d.proposed}\"\n${d.reason}\nEvidencia: ${d.evidence}")
        } }
        item { ReviewPages(auditPage, maxOf(1, (events.size + 24) / 25), events.size) { auditPage = it } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirmar revisión") },
        text = { Text("Guardar estado ${state.name} y la propuesta \"$proposed\" en el expediente local de este Excel. No se aplicarán cambios al archivo ni a Firestore.") },
        confirmButton = { TextButton(onClick = { confirm = false; onSave(item, proposed, reason, evidence, state) }) { Text("Confirmar decisión") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
}

@Composable
private fun ReviewCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF03152C), border = BorderStroke(1.dp, Color(0xFF07568B))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}
@Composable
private fun <T> ReviewMenu(label: String, value: String, options: List<T>, text: (T) -> String, select: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: $value") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(text(option)) }, onClick = { open = false; select(option) }) }
        }
    }
}
@Composable
private fun ReviewPages(page: Int, pages: Int, count: Int, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { change(page - 1) }, enabled = page > 0) { Text("Anterior") }
        Text("${page + 1}/$pages · $count", modifier = Modifier.padding(top = 12.dp))
        TextButton(onClick = { change(page + 1) }, enabled = page + 1 < pages) { Text("Siguiente") }
    }
}
