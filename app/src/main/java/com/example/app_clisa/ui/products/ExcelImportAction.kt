package com.example.app_clisa.ui.products

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.app_clisa.data.excel.*
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.*
import java.io.File

private data class ReadExcelFile(val name: String, val bytes: Long, val report: ExcelInventory, val sha256: String)

/** UI coordinator: same local reader and read-only comparison, presented as the three implemented steps. */
@Composable
internal fun ExcelImportLegacyAction(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExcelInventory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var fileBytes by remember { mutableStateOf<Long?>(null) }
    var comparison by remember { mutableStateOf<InventoryComparison?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ComparisonProgress?>(null) }
    var compareError by remember { mutableStateOf<String?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var showWizard by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<InventoryPreview?>(null) }
    var fileSha256 by remember { mutableStateOf<String?>(null) }
    var sourceUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showFinal by remember(fileSha256) { mutableStateOf(false) }
    var showClassification by remember(fileSha256) { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    var issueJournal by remember(fileSha256) { mutableStateOf<IssueReviewJournal?>(null) }
    val issueCatalog = remember(result, fileSha256, comparison) {
        val report = result; val hash = fileSha256
        if (report != null && hash != null) IssueReviewCatalog(hash, report, comparison?.inventory) else null
    }
    val assessment = remember(result, fileSha256) { result?.let { ExcelCompletenessValidator.assess(it, fileSha256) } }
    val dryRun by produceState<SyncPlan?>(null, result, comparison, fileSha256) {
        value = null
        val read = result; val compared = comparison; val hash = fileSha256
        if (read != null && compared != null && hash != null) {
            value = withContext(Dispatchers.Default) { InventoryDryRunPlanner.generate(compared,
                ReportReadEvidence.fromReadResult(read, fileName, hash)) }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            sourceUri = uri
            fileSha256 = null; showIssues = false
            reading = true; error = null; result = null; comparison = null; compareError = null
            preview = null; showPreview = false; showWizard = true; fileName = ""; fileBytes = null; progress = null
            operationJob = scope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        val cancellation = coroutineContext
                        val metadata = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to
                                    (if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0 } else null)
                            } else null
                        }
                        val name = metadata?.first ?: "archivo.xlsx"
                        withContext(Dispatchers.Main) { fileName = name; fileBytes = metadata?.second }
                        require(name.endsWith(".xlsx", ignoreCase = true)) { "Selecciona un archivo Excel .xlsx." }
                        val temporary = File.createTempFile("clisa-excel-", ".xlsx", context.cacheDir)
                        try {
                            requireNotNull(context.contentResolver.openInputStream(uri)) { "No se pudo abrir el archivo seleccionado." }.use { input ->
                                temporary.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    var total = 0L
                                    while (true) {
                                        cancellation.ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        require(total <= XlsxInventoryReader.MAX_FILE_BYTES) { "El archivo supera el límite de 25 MB." }
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            ReadExcelFile(name, temporary.length(), XlsxInventoryReader().read(temporary) { cancellation.ensureActive() },
                                temporary.inputStream().use { SyncPlanIdentity.fileSha256(it) })
                        } finally { temporary.delete() }
                    }
                    fileName = loaded.name; fileBytes = loaded.bytes; result = loaded.report; fileSha256 = loaded.sha256
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: "El archivo no tiene un formato válido."
                    else "No se pudo leer el Excel. Comprueba que sea un .xlsx válido, sin contraseña y con las columnas requeridas." }
                finally { reading = false }
            }
        }
    }
    fun analyze() {
        if (reading || comparing) return
        if (preview != null) { showPreview = true; return }
        val report = result ?: return
        comparing = true; compareError = null; progress = null
        operationJob = scope.launch {
            try {
                val snapshot = FirestoreInventoryReader().read { progress = it }
                progress = ComparisonProgress("comparación local", snapshot.products.size, snapshot.existences.size)
                val compared = withContext(Dispatchers.Default) {
                    val cancellation = coroutineContext
                    val value = InventoryComparator.compare(report, snapshot) { cancellation.ensureActive() }
                    val view = InventoryPreviewBuilder.build(value, report)
                    cancellation.ensureActive()
                    value to view
                }
                comparison = compared.first; preview = compared.second; showPreview = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                compareError = when {
                    e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "Tu cuenta no tiene autorización para leer el inventario. Revisa su estado, permisos y las reglas publicadas. No se intentó otro acceso."
                    e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
                        "Se alcanzó la cuota de lectura. Espera a que se restablezca antes de volver a comparar."
                    e is IllegalStateException -> e.message ?: "No se pudo verificar el acceso."
                    else -> "No se completó la lectura. Comprueba la conexión. Reintentar volverá a consumir lecturas; no se genera un resultado parcial."
                }
            } finally { comparing = false }
        }
    }
    Surface(onClick = { showWizard = true; if (preview != null) showPreview = true }, enabled = !reading && !comparing,
        modifier = modifier, color = Color.Transparent, shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, Color(0xFF00E77F)), shadowElevation = 6.dp) {
        Row(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF00964E), Color(0xFF004837))))
            .padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ImportExcelSymbol()
            Text("Importar Excel", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    if (showWizard) {
        when {
            showClassification && result != null && comparison != null && fileSha256 != null ->
                ProductClassificationHost(result!!, comparison!!, fileSha256!!) { showClassification = false }
            showFinal && result != null && comparison != null && dryRun != null ->
                ImportFinalScreen(dryRun!!, result!!, comparison!!, sourceUri) { showFinal = false }
            showIssues && issueCatalog != null -> IssueReviewHost(issueCatalog, { issueJournal = it }, { showIssues = false })
            reading || comparing -> ImportAnalysisScreen(fileName, fileBytes, result, reading, progress) {
                if (reading) error = "Lectura cancelada. Selecciona de nuevo el archivo para leerlo completo."
                else compareError = "Análisis cancelado. Las lecturas realizadas ya cuentan para la cuota; reintentar iniciará una nueva pasada."
                operationJob?.cancel()
            }
            showPreview && preview != null && comparison != null && result != null -> InventoryPreviewScreen(
                preview!!, comparison!!, result!!, fileName,
                onClose = { showPreview = false }, onReadSummary = { showPreview = false },
                safetyContent = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IssueReviewEntry(issueCatalog, issueJournal) { showIssues = true }
                    ImportFinalEntry(dryRun != null) { showFinal = true }
                    OutlinedButton(onClick = { showClassification = true }, enabled = comparison != null) { Text("Clasificar categorías y marcas") }
                    assessment?.let { ExportSafetyReview(result!!, it,
                        dryRun?.let { plan -> issueJournal?.let { issueCatalog?.reviewDryRun(plan, it)?.plan } ?: plan }) }
                } })
            else -> ImportSelectionScreen(fileName, fileBytes, result, error ?: compareError, preview != null,
                onBack = {
                    showWizard = false
                },
                onPick = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/zip")) },
                onClear = { fileSha256 = null; fileName = ""; fileBytes = null; result = null; comparison = null; preview = null; error = null; compareError = null; showPreview = false },
                onAnalyze = { analyze() },
                connectionContent = { RenderImportActions(null, null, null, null, reading || comparing, onlyCheck = true) },
                safetyContent = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IssueReviewEntry(issueCatalog, issueJournal) { showIssues = true }
                    ImportFinalEntry(dryRun != null) { showFinal = true }
                    OutlinedButton(onClick = { showClassification = true }, enabled = comparison != null) { Text("Clasificar categorías y marcas") }
                    if (result != null && assessment != null) ExportSafetyReview(result!!, assessment, dryRun)
                } })
        }
    }
}
