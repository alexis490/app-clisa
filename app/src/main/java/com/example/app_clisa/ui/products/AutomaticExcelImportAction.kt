package com.example.app_clisa.ui.products

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.app_clisa.data.backend.ClisaBackendClient
import com.example.app_clisa.data.backend.FirebaseBackendSession
import com.example.app_clisa.data.backend.BackendFailure
import com.example.app_clisa.data.backend.BackendProblem
import com.example.app_clisa.data.backend.ImportSummaryExporter
import com.example.app_clisa.data.excel.ImportSummaryWorkbook
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val ImportNavy = Color(0xFF031B35)
private val ImportGreen = Color(0xFF00E6A0)
private val ImportBorder = Color(0xFF07568B)
private val ImportMuted = Color(0xFFA9C8EC)
private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
private val activeImportStates = setOf("COMPARISON", "CLASSIFICATION", "SYNCING")

/** Backend owns parsing, scope validation, classification, proposals and all inventory writes. */
@Composable
internal fun ExcelImportAction(modifier: Modifier = Modifier, initialJob: JSONObject? = null, backend: ClisaBackendClient? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(backend) { backend ?: ClisaBackendClient(FirebaseBackendSession()) }
    val preferences = remember { context.getSharedPreferences("excel-import-resume", 0) }
    val uid = client.currentUid
    var savedImportId by remember(uid) { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf(initialJob) }
    var pendingImportId by remember { mutableStateOf<String?>(null) }
    var inspectedStep by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var bytes by remember { mutableStateOf(0L) }
    var file by remember { mutableStateOf<File?>(null) }
    var mapping by remember { mutableStateOf<JSONArray?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var confirmation by remember { mutableStateOf(false) }
    var syncConnection by remember { mutableStateOf("CHECKING") }
    var connectionAttempt by remember { mutableIntStateOf(0) }
    var details by remember { mutableStateOf(false) }; var showPreview by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<String?>(null) }
    var detailItems by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var nextOffset by remember { mutableStateOf<Int?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var progressNotice by remember { mutableStateOf<String?>(null) }
    var checkingFormat by remember { mutableStateOf(false) }
    var formatChecks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var formatReady by remember { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var preparedExport by remember { mutableStateOf<File?>(null) }
    var exportFolder by remember { mutableStateOf<File?>(null) }
    var exportOperation by remember { mutableStateOf<Job?>(null) }
    fun discardExport() { preparedExport?.delete(); preparedExport=null; exportFolder?.deleteRecursively(); exportFolder=null }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ImportSummaryWorkbook.MIME)) { uri ->
        val source=preparedExport
        if(uri==null){discardExport();exportBusy=false;exportMessage=null}
        else if(source==null){exportBusy=false;exportMessage="No se encontró el archivo preparado. Vuelve a exportar."}
        else exportOperation=scope.launch {
            try {
                exportMessage="Guardando resumen…"
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri,"w")).use { output -> source.inputStream().use { input ->
                        val buffer=ByteArray(8192)
                        while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n)}
                        output.flush()
                    } }
                }
                exportMessage="Resumen exportado correctamente"
            } catch(e:CancellationException){throw e}
            catch(e:Exception){exportMessage="No se pudo guardar el resumen. Comprueba el espacio y los permisos de la ubicación elegida."}
            finally{discardExport();exportBusy=false}
        }
    }
    fun exportSummary(id:String) {
        if(exportBusy)return
        exportBusy=true;exportMessage="Preparando resumen…"
        val folder=File(context.cacheDir,"clisa-resumen-${java.util.UUID.randomUUID()}")
        exportFolder=folder
        exportOperation=scope.launch {
            try {
                withContext(Dispatchers.IO){check(folder.mkdirs())}
                preparedExport=ImportSummaryExporter.prepare(client,id,folder){exportMessage=it}
                currentCoroutineContext().ensureActive()
                export.launch(ImportSummaryWorkbook.suggestedName())
            }catch(e:CancellationException){discardExport();exportBusy=false;throw e}
            catch(e:Exception){discardExport();exportBusy=false;exportMessage=when(e){is BackendFailure->"No se pudo exportar el resumen: ${e.message}";is IllegalArgumentException->e.message?:"No se pudo generar el resumen.";else->"No se pudo generar el Excel. Comprueba el espacio disponible y vuelve a intentarlo."}}
        }
    }

    fun rememberJob(value: JSONObject) {
        job = value
        if (value.optString("status") == "SYNCING") syncConnection = "CONNECTED"
        if (value.has("id") && uid != null) preferences.edit().putString(uid, value.getString("id")).apply()
    }
    suspend fun advance(initial: JSONObject) {
        var value = initial; rememberJob(value)
        while (value.optString("status") in activeImportStates) {
            currentCoroutineContext().ensureActive()
            try { value = client.automaticJob(value.getString("id"), "advance"); progressNotice = null; rememberJob(value); delay(750) }
            catch (e: BackendFailure) {
                if (e.problem == BackendProblem.RATE_LIMIT) { progressNotice = "Esperando la siguiente ventana de solicitudes. El proceso continuará automáticamente."; delay(61000) }
                else if (e.problem == BackendProblem.BUSY) { progressNotice = "La solicitud anterior sigue en curso; recuperando el avance…"; delay(3000) }
                else throw e
            }
        }
        if (value.optString("status") == "REVIEW_READY" && value.optJSONObject("review") == null) {
            progressNotice = "Preparando el resumen del análisis…"
            rememberJob(client.automaticJob(value.getString("id"), "review")); progressNotice = null
        }
    }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; progressNotice = null
        operation = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if(showPreview && job?.optString("status") in setOf("PREVIEW", "SYNCING")) syncConnection = "ERROR"; error = e.message ?: "No se completó la operación. Puedes reanudar el proceso guardado." }
            finally { busy = false; progressNotice = null; checkingFormat = false }
        }
    }
    fun upload(mappings: JSONObject = JSONObject()) {
        if (!formatReady && mapping == null) return
        val source = file ?: return
        run {
            val hash = withContext(Dispatchers.IO) { source.inputStream().use { com.example.app_clisa.data.excel.SyncPlanIdentity.fileSha256(it) } }
            val pending = uid?.let { runCatching { JSONObject(preferences.getString("pending-$it", null).orEmpty()) }.getOrNull() }
            val operationId = pendingImportId ?: pending?.takeIf { it.optString("hash") == hash && it.optString("filename") == name }?.optString("id")?.takeIf { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) } ?: java.util.UUID.randomUUID().toString()
            pendingImportId = operationId
            uid?.let { preferences.edit().putString("pending-$it", JSONObject().put("id", operationId).put("hash", hash).put("filename", name).toString()).apply() }
            val value = client.uploadAutomatic(source, name, mappings, importId = operationId) { progressNotice = it }
            progressNotice = null
            if (value.optString("status") == "MAPPING_REQUIRED") mapping = value.getJSONArray("mappingRequired")
            else {
                uid?.let { preferences.edit().remove("pending-$it").apply() }
                mapping = null; source.delete(); file = null
                advance(value)
            }
        }
    }
    suspend fun inspectFormat(source: File) {
        checkingFormat = true; formatReady = false; formatChecks = emptySet()
        try {
            val result = client.inspectExcelFormat(source, name)
            for (key in listOf("serial", "code", "name", "quantity")) {
                currentCoroutineContext().ensureActive()
                if (key in result.detected) { formatChecks = formatChecks + key; delay(140) }
            }
            formatReady = result.ready
            if (!result.ready) error = "Faltan campos del formato esperado. Revisa los grupos [código] producto, las series y las cantidades antes de analizar."
        } finally { checkingFormat = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            savedImportId = null
            job = null; pendingImportId = null; inspectedStep = null; mapping = null; details = false; showPreview = false; checkingFormat = true; formatReady = false; formatChecks = emptySet()
            file?.delete(); file = null; name = ""; bytes = 0
            val source = withContext(Dispatchers.IO) {
                val cancellation = currentCoroutineContext()
                val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "inventario.xlsx"
                require(filename.substringAfterLast('.', "").lowercase() in setOf("xlsx", "xls", "csv")) { "Selecciona XLSX, XLS o CSV." }
                withContext(Dispatchers.Main) { name = filename; bytes = 0 }
                val temp = File.createTempFile("clisa-prueba-", ".bin", context.cacheDir)
                try {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(8192); var size = 0L
                        while (true) { cancellation.ensureActive(); val n = input.read(buffer); if (n < 0) break
                            size += n; require(size <= ClisaBackendClient.MAX_EXCEL_BYTES) { "El archivo supera 25 MB." }; output.write(buffer, 0, n) }
                    } }
                    temp
                } catch (e: Exception) { temp.delete(); throw e }
            }
            file?.delete(); file = source; bytes = source.length()
            inspectFormat(source)
        }
    }
    LaunchedEffect(visible) {
        if (visible && !busy) {
            if (job?.optString("status") == "REVIEW_READY" && job?.optJSONObject("review") == null) {
                run { advance(job!!) }; return@LaunchedEffect
            }
            val saved = if (job?.optString("status") in activeImportStates) job?.optString("id") else null
            saved?.let { id -> run { advance(client.automaticJob(id)) } }
        }
    }
    DisposableEffect(Unit) { onDispose { operation?.cancel(); exportOperation?.cancel(); discardExport(); file?.delete() } }
    Surface(onClick = {
        if (job == null && file == null) savedImportId = uid?.let { preferences.getString(it, null) }
        visible = true
    }, modifier = modifier, color = Color(0xFF006B43), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, ImportGreen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImportExcelSymbol(); Text("Importar Excel", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
    if (!visible) return
    val current = job
    val status = current?.optString("status").orEmpty()
    val staged = current?.optBoolean("reviewBeforeClassification") == true
    val finished = status in setOf("COMPLETED", "PARTIAL")
    val workflowStep = when { finished || status == "SYNCING" -> 5; status == "CLASSIFICATION" -> 4; status == "PREVIEW" -> if (showPreview || !staged) 5 else 4; status == "REVIEW_READY" -> 3; (busy && !checkingFormat) || status in activeImportStates -> 2; else -> 1 }
    val step = if (finished) 5 else inspectedStep?.coerceIn(1, workflowStep) ?: workflowStep
    val contentScroll = remember(step, details) { androidx.compose.foundation.ScrollState(0) }
    LaunchedEffect(visible, current?.optString("id"), step == 5 && !finished, connectionAttempt) {
        if (visible && step == 5 && !finished && status == "PREVIEW") {
            syncConnection = "CHECKING"
            error = null
            try {
                val fresh = client.automaticJob(current!!.getString("id"))
                // This authenticated response reads the draft from Firestore through Render.
                syncConnection = "CONNECTED"
                if (fresh.optString("status") == "PREVIEW") rememberJob(fresh)
                else run { advance(fresh) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { syncConnection = "ERROR"; error = e.message ?: "No se pudo comprobar la conexión." }
        }
    }
    fun openDetails(kind: String?) {
        filter = kind; detailItems = current?.optJSONArray("items")?.objects().orEmpty()
        nextOffset = if (current == null || current.isNull("nextOffset")) null else current.optInt("nextOffset")
        details = true
    }
    fun closeFlow() {
        operation?.cancel(); exportOperation?.cancel(); discardExport(); exportBusy=false; busy = false; visible = false; inspectedStep = null
        if (finished) {
            uid?.let { preferences.edit().remove(it).apply() }
            job = null; name = ""; bytes = 0; showPreview = false; details = false
        }
    }
    fun goBack() {
        if (details) details = false
        else if (finished) closeFlow()
        else if (step > 1 && (staged || current == null)) inspectedStep = step - 1
        else if (showPreview && !finished) showPreview = false
        else closeFlow()
    }
    val title = if (details) { if (filter == "issues") "Incidencias por producto" else if (filter == "changes") "Detalle de cambios" else "Clasificación de productos" }
        else when { finished -> "Importación completada"; step == 5 -> "Sincronizar productos"; step == 4 -> "Clasificación de productos"; step == 3 -> "Revisar análisis"; step == 2 -> "Analizando Excel"; else -> "Importar inventario" }
    InventoryImportFrame(title, onBack = { goBack() }) {
        if (!details && step == 1) InventorySelectionSteps()
        if (!details && step == 2 && (staged || status != "CLASSIFICATION")) InventorySelectionSteps(2, "Leyendo Excel y comparando con Firestore")
        if (!details && step == 3 && (staged || status == "REVIEW_READY")) InventorySelectionSteps(3, "Analiza las diferencias antes de clasificar")
        if (!details && step in 4..5 && !finished) InventorySelectionSteps(step, if (step == 4) "Completando categoría, marca y descripción" else "Actualizar inventario en Firebase")
        Column(Modifier.weight(1f).verticalScroll(contentScroll).padding(12.dp).testTag("automatic-import-content"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (details) {
                if (filter == "issues") InventoryProductIssues(current ?: JSONObject()) else InventoryDetails(current ?: JSONObject(), detailItems, false, filter == "changes")
                if (filter != "issues") nextOffset?.let { offset -> OutlinedButton(enabled = !detailLoading, onClick = {
                    detailLoading = true
                    scope.launch { try { val page = client.automaticJob(current!!.getString("id"), offset = offset); detailItems = detailItems + page.getJSONArray("items").objects(); nextOffset = if (page.isNull("nextOffset")) null else page.getInt("nextOffset") }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { detailLoading = false } }
                }) { Text(if (detailLoading) "Cargando…" else "Cargar más productos") } }
            } else if (staged && step == 1) {
                InventorySelection(name.ifBlank { current!!.optString("filename") }, if(bytes>0) bytes else current!!.optLong("bytes"), setOf("serial", "code", "name", "quantity"), false, true, !busy && !current!!.optBoolean("confirmed")) { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv")) }
            } else if (staged && step == 2) {
                InventoryExcelAnalysis(current, name, bytes)
            } else if (step == 3 && (staged || status == "REVIEW_READY")) {
                InventoryReviewReady(current!!) { openDetails("issues") }
            } else if (step == 4) {
                InventoryClassificationStage(if(status in setOf("SYNCING", "COMPLETED", "PARTIAL")) JSONObject(current.toString()).put("status", "PREVIEW") else current!!)
            } else if (step == 5 && !finished) {
                InventorySyncPanel(current!!, syncConnection, { connectionAttempt++ }, { openDetails("changes") }, { openDetails(null) }, { openDetails("issues") })
            } else if (finished) {
                InventoryCompleted(current!!, { openDetails("issues") }, { openDetails("changes") }, { closeFlow() },
                    { exportSummary(current!!.getString("id")) }, exporting=exportBusy, exportMessage=exportMessage)
            } else if (step == 2 && status != "CLASSIFICATION") {
                InventoryExcelAnalysis(current, name, bytes)
            } else if (step == 2 || status == "SYNCING") {
                InventoryProcessing(status, current?.optInt("cursor") ?: 0, current?.optInt("total") ?: 0)
                Spacer(Modifier.height(30.dp))
                InventoryCard { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("◷", color = Color(0xFF008DFF), style = MaterialTheme.typography.headlineMedium)
                    Column { Text(if (status == "SYNCING") "Sincronizando…" else "Procesando…", fontWeight = FontWeight.Bold); Text("Por favor, no cierres esta pantalla.", style = MaterialTheme.typography.bodySmall) }
                } }
            } else if (mapping == null) {
                InventorySelection(name, bytes, formatChecks, checkingFormat, formatReady, !busy) { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "application/octet-stream")) }
                if (current == null && file == null) savedImportId?.let { id ->
                    InventoryCard {
                        Text("Hay una importación guardada. Puedes reanudarla o seleccionar un archivo nuevo.", color = ImportMuted, style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, modifier = Modifier.testTag("resume-saved-import"), onClick = {
                            run { advance(client.automaticJob(id)); savedImportId = null }
                        }) { Text("Reanudar importación guardada") }
                    }
                }
            }
            progressNotice?.let { Text(it, color = Color(0xFFFFBC39), style = MaterialTheme.typography.bodySmall) }
            error?.let { message -> InventoryCard {
                Text(message, color = Color(0xFFFFBC39))
                if (!busy && current != null && !(step == 5 && status == "PREVIEW" && syncConnection == "ERROR")) OutlinedButton(onClick = { run { advance(client.automaticJob(current.getString("id"))) } }) { Text("Reanudar proceso") }
                else if (!busy && file != null) OutlinedButton(onClick = { run { inspectFormat(file!!) } }) { Text("Reintentar comprobación") }
            } }
        }
        if (!details && step == 3 && (staged || status == "REVIEW_READY")) InventoryReviewFooter((status != "REVIEW_READY" || !busy) && (current?.optJSONObject("review")?.optInt("validProducts") ?: 0) > 0) {
            showPreview = false
            if (status == "REVIEW_READY") {
                inspectedStep = null
                run { progressNotice = "Iniciando clasificación…"; advance(client.automaticJob(current!!.getString("id"), "classify")) }
            } else inspectedStep = 4
        }
        if (!details && step == 5 && !finished) InventorySyncFooter(!busy && syncConnection == "CONNECTED" && status == "PREVIEW", status == "SYNCING", { if(!busy && syncConnection == "CONNECTED") confirmation = true }, { closeFlow() })
        if (!details && step == 4) InventoryClassificationFooter((!busy && status == "PREVIEW") || status in setOf("SYNCING", "COMPLETED", "PARTIAL")) { inspectedStep = null; showPreview = true }
        if (!details && step == 2 && (staged || status != "CLASSIFICATION")) {
            if (staged && workflowStep >= 3) Button(onClick = { inspectedStep = 3 }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("return-to-review")) { Text("Continuar a revisión") }
            else InventoryAnalysisFooter { closeFlow() }
        }
        if (!details && step == 1 && mapping == null) InventoryAnalyzeButton((staged && current != null) || (formatReady && !busy && file != null), checkingFormat, name.isNotBlank() || current != null) { if(staged && current != null) inspectedStep = 2 else upload() }
        if (!details && mapping != null && !busy) Column(Modifier.heightIn(max = 440.dp).padding(12.dp)) { AutomaticColumnMapping(mapping!!) { values -> upload(values) } }
    }
    if (confirmation) InventorySyncConfirmation({ confirmation = false }) {
        confirmation = false
        if (!busy && job?.optString("status") == "PREVIEW" && syncConnection == "CONNECTED") run { advance(client.automaticJob(job!!.getString("id"), "confirm")) }
    }
}

@Composable
private fun ColumnScope.AutomaticColumnMapping(sheets: JSONArray, onApply: (JSONObject) -> Unit) {
    val values = remember(sheets) { mutableStateMapOf<String, String>() }
    var invalid by remember { mutableStateOf(false) }
    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Asigna columnas solo en las hojas no reconocidas. Usa números de columna desde 1.", fontWeight = FontWeight.Bold) }
        sheets.objects().forEach { sheet ->
            val name = sheet.getString("sheet")
            item { Text(name, color = ImportGreen); Text(sheet.getJSONArray("rows").toString(), style = MaterialTheme.typography.bodySmall) }
            listOf("headerRow" to "Fila de encabezados", "code" to "Columna de código", "name" to "Columna de nombre", "serial" to "Columna de serie/lote", "quantity" to "Columna de cantidad").forEach { (key, label) ->
                item { OutlinedTextField(values["$name/$key"] ?: "", { values["$name/$key"] = it.filter(Char::isDigit) }, label = { Text(label) }, singleLine = true) }
            }
        }
    }
    if (invalid) Text("Completa números válidos y columnas distintas.", color = Color(0xFFFFBC39))
    Button(onClick = {
        val result = JSONObject(); invalid = false
        for (sheet in sheets.objects()) {
            val name = sheet.getString("sheet"); val map = JSONObject(); val columns = mutableSetOf<Int>()
            for (key in listOf("headerRow", "code", "name", "serial", "quantity")) {
                val n = values["$name/$key"]?.toIntOrNull()
                if (n == null || n < 1 || (key != "headerRow" && !columns.add(n))) { invalid = true; return@Button }
                map.put(key, if (key == "headerRow") n else n - 1)
            }
            result.put(name, map)
        }
        onApply(result)
    }) { Text("Aplicar y continuar") }
}

