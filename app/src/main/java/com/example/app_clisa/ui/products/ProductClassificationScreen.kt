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

@Composable internal fun ProductClassificationHost(excel: ExcelInventory, comparison: InventoryComparison, hash: String, onBack: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val uid = FirebaseAuth.getInstance().currentUser?.uid
    var review by remember(hash) { mutableStateOf<ClassificationReview?>(null) }
    var journal by remember(hash) { mutableStateOf<IssueReviewJournal?>(null) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var canEdit by remember { mutableStateOf(false) }
    val store = remember(uid) { uid?.let { ClassificationReviewStore(File(context.noBackupFilesDir, "product-classification"), it) } }
    fun load() {
        busy = true; error = null; review = null; journal = null; canEdit = false
        scope.launch {
            try {
                val principal = IssueReviewAuthorization.verify(); check(principal.uid == uid)
                val catalog = FirestoreClassificationCatalog().read()
                val loaded = withContext(Dispatchers.Default) {
                    val rules = context.assets.open("classification-rules.tsv").bufferedReader().use { ClassificationRules.read(it.readText()) }
                    ClassificationReview(hash, ProductClassifier.products(excel, requireNotNull(comparison.inventory)), catalog, rules)
                }
                val saved = withContext(Dispatchers.IO) { requireNotNull(store).load(hash).also { store.saveManifest(loaded, it) } }
                review = loaded; journal = saved; canEdit = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "No se pudieron leer los catálogos completos o recuperar el expediente. Revisa permisos, conexión o límites; no se usó información parcial." }
            finally { busy = false }
        }
    }
    ProductClassificationScreen(review, journal, busy, error, onBack, ::load, canEdit) { choices ->
        val snapshot = review; val revision = journal?.revision
        if (snapshot != null && revision != null) {
            busy = true; error = null
            scope.launch {
                try {
                    val principal = IssueReviewAuthorization.verify(); check(principal.uid == uid)
                    journal = withContext(Dispatchers.IO) { requireNotNull(store).approve(snapshot, choices, principal.role, revision, System.currentTimeMillis()) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = if (e is IllegalArgumentException || e is IllegalStateException) e.message else "No se pudo guardar la aprobación local." }
                finally { busy = false }
            }
        }
    }
}

@Composable internal fun ProductClassificationScreen(review: ClassificationReview?, journal: IssueReviewJournal?, busy: Boolean,
    error: String?, onBack: () -> Unit, onRead: () -> Unit, canEdit: Boolean = true, onApprove: (List<ClassificationChoice>) -> Unit) {
    var page by remember(review?.catalog?.digest) { mutableIntStateOf(0) }
    var selected by remember(review) { mutableStateOf<Pair<String, String>?>(null) }
    var target by remember(selected) { mutableStateOf("") }; var reason by remember(selected) { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<List<ClassificationChoice>?>(null) }
    val latest = remember(journal) { journal?.latest.orEmpty() }
    val bulk = remember(review, journal) { review?.classified.orEmpty().flatMap { p -> listOf(p.category, p.brand).mapNotNull { s ->
        if (!s.preserved && s.state == ClassificationState.ASIGNADO && s.proposed != null && journal != null && review!!.approved(latest, p.product.key, s.field) == null)
            ClassificationChoice(p.product.key, s.field, s.proposed, "Coincidencia inequívoca revisada por reglas deterministas.", true) else null } } }
    ImportFlowFrame("Clasificación por reglas", "Sugerencias por producto · Sin IA ni cambios de stock", 3, onBack) {
        LazyColumn(Modifier.weight(1f).testTag("classification-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ClassificationCard {
                Text("Categorías y marcas existentes; solo coincidencias de palabras completas.")
                Text("Las aprobaciones se guardan en el expediente. No crean productos ni desbloquean el inventario.")
                if (error != null) Text(error, color = Color(0xFFFFBC39))
                OutlinedButton(onClick = onRead, enabled = !busy, modifier = Modifier.testTag("classification-read")) { Text(if (review == null) "Cargar catálogos y clasificar" else "Actualizar catálogos y revalidar") }
                if (review != null) {
                    Text("${review.classified.size} productos únicos · ${review.current(journal ?: IssueReviewJournal(review.fileSha256)).size} aprobaciones vigentes")
                    Text("${review.rules.version}\nCatálogo ${review.catalog.digest.take(16)}", style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { confirmation = bulk }, enabled = canEdit && !busy && bulk.isNotEmpty(), modifier = Modifier.testTag("classification-bulk")) { Text("Aprobar inequívocas (${bulk.size})") }
                }
            } }
            items(review?.classified.orEmpty().drop(page * 20).take(20), key = { it.product.key }) { p -> ClassificationCard {
                Text(p.product.name, style = MaterialTheme.typography.titleSmall); Text("${p.product.sku ?: "SKU desconocido"} · ID ${p.product.documentId ?: "sin identidad"}")
                listOf(p.category, p.brand).forEach { s ->
                    Text("${s.field}: ${s.state} · ${s.proposed ?: "pendiente"}", color = Color(0xFF8ACBFF))
                    Text(s.explanation, style = MaterialTheme.typography.bodySmall)
                    val approved = review?.approved(latest, p.product.key, s.field)
                    if (approved != null) Text("Aprobado: ${approved.proposed} · ${approved.reviewer}")
                    OutlinedButton(onClick = { selected = p.product.key to s.field }, enabled = canEdit && !busy && p.product.identityValid && !s.preserved,
                        modifier = Modifier.testTag("classification-edit-${p.product.key}-${s.field}")) { Text("Revisar ${s.field}") }
                }
                Text(if (p.descriptionPending) "Descripción pendiente para futura integración de Gemini; no se genera texto." else "Descripción original conservada.")
            } }
            item { Row {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Anterior") }
                Text("Página ${page + 1}", Modifier.padding(12.dp))
                TextButton(onClick = { page++ }, enabled = (page + 1) * 20 < (review?.classified?.size ?: 0)) { Text("Siguiente") }
            } }
        }
    }
    if (selected != null && review != null) {
        val (key, field) = selected!!; val references = if (field == "categoriaId") review.catalog.categories else review.catalog.brands
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Revisión individual · $field") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Elige un ID activo del catálogo. Documenta la decisión si hay conflicto.")
                var expanded by remember { mutableStateOf(false) }
                Box { OutlinedButton(onClick = { expanded = true }) { Text(references.find { it.id == target }?.let { "${it.name} (${it.id})" } ?: "Elegir referencia existente") }
                    DropdownMenu(expanded, { expanded = false }) { references.filter { it.active }.forEach { r -> DropdownMenuItem(text = { Text("${r.name} (${r.id})") }, onClick = { target = r.id; expanded = false }) } } }
                OutlinedTextField(reason, { reason = it.take(4000) }, label = { Text("Justificación") }, modifier = Modifier.testTag("classification-reason"))
            }
        }, confirmButton = { TextButton(enabled = target.isNotBlank() && reason.isNotBlank(), onClick = {
            confirmation = listOf(ClassificationChoice(key, field, target, reason, false)); selected = null
        }) { Text("Revisar aprobación") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancelar") } })
    }
    if (confirmation != null) AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Confirmar clasificación") },
        text = { Text("Guardar ${confirmation!!.size} aprobaciones en el expediente de este Excel. Los conflictos solo se aceptan individualmente. No se escribe en Firestore ni se habilita stock.") },
        confirmButton = { TextButton(onClick = { val choices = confirmation!!; confirmation = null; onApprove(choices) }) { Text("Confirmar aprobaciones") } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancelar") } })
}
@Composable private fun ClassificationCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color(0xFF03152C), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF07568B))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}
