package com.example.app_clisa.ui.products

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

private data class PendingPackage(val uid: String, val hash: String, val uri: Uri, val contract: String, val review: String)

@Composable internal fun ImportFinalEntry(ready: Boolean, open: () -> Unit) {
    OutlinedButton(onClick = open, enabled = ready, modifier = Modifier.testTag("open-final-summary")) { Text("Resumen final y expediente") }
}

@Composable internal fun ImportFinalScreen(plan: SyncPlan, excel: ExcelInventory, comparison: InventoryComparison,
    sourceUri: Uri?, onBack: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val summary = remember(plan, excel) { ImportFinalization.summarize(plan, excel) }
    var pending by remember { mutableStateOf<PendingPackage?>(null) }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    var simulation by remember(plan.planId) { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val request = pending; pending = null
        if (uri != null && request != null) {
            busy = true
            scope.launch {
                try {
                    check(IssueReviewAuthorization.verify().uid == request.uid) { "La sesión cambió." }
                    require(uri != request.uri) { "Selecciona un destino diferente del Excel original." }
                    if (uri.authority == request.uri.authority && android.provider.DocumentsContract.isDocumentUri(context, uri) && android.provider.DocumentsContract.isDocumentUri(context, request.uri))
                        require(android.provider.DocumentsContract.getDocumentId(uri) != android.provider.DocumentsContract.getDocumentId(request.uri)) { "No se puede sobrescribir el Excel original." }
                    withContext(Dispatchers.IO) {
                        val temporary = File.createTempFile("clisa-export-", ".xlsx", context.cacheDir)
                        try {
                            requireNotNull(context.contentResolver.openInputStream(request.uri)).use { input ->
                                temporary.outputStream().use { output ->
                                    val buffer = ByteArray(8192); var total = 0L
                                    while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break
                                        total += n; require(total <= XlsxInventoryReader.MAX_FILE_BYTES); output.write(buffer, 0, n) }
                                }
                            }
                            requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { out ->
                                ImportFinalization.writePackage(temporary, out, request.hash, request.contract, request.review)
                            }
                        } finally { temporary.delete() }
                    }
                    message = "Expediente exportado. No autoriza ni ejecuta escrituras; requiere revisión administrativa independiente."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "No se pudo exportar el expediente completo. No se modificó el inventario." }
                finally { busy = false }
            }
        }
    }
    val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val bytes = input.readBytesBounded(256 * 1024); JSONObject(String(bytes, Charsets.UTF_8)) } }
                require(json.getInt("schemaVersion") == 1 && json.getString("environment") == "EMULATOR" && !json.getBoolean("production"))
                require(json.getString("fileSha256") == plan.fileSha256 && json.optString("sourcePlanId", json.getString("planId")) == plan.planId) { "El informe corresponde a otro archivo o plan." }
                val status = json.getString("status"); require(status in setOf("COMPLETED", "REQUIRES_REVIEW", "FAILED", "PARTIAL"))
                val groups = json.getInt("appliedGroups"); require(groups >= 0)
                simulation = "Informe local de Emulator: $status · $groups grupos aplicados · ${json.getString("runId").take(100)}. Informativo; no prueba autenticidad ni autoriza producción."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Informe inválido o de otro contexto. No se aceptó como resultado de este plan." }
        }
    }
    ImportFlowFrame("Resumen de importación", "Expediente administrativo · Sin escrituras reales", 3, onBack) {
        LazyColumn(Modifier.weight(1f).testTag("import-final-summary"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { FinalCard {
                Text("Estado: PREPARADO PARA REVISIÓN · PRODUCCIÓN BLOQUEADA", color = Color(0xFFFFBC39))
                Text("${summary.products} productos analizados · ${excel.errorCount} incidencias")
                Text("Operaciones seguras verificadas: ${summary.verified} · Bloqueadas: ${summary.blocked} · Sin cambios: ${summary.unchanged}")
                Text("Stock desde cantidades válidas del Excel · ${summary.reviewPending} ausencias conservadas para revisión")
                Text("Escenario potencial: ${summary.estimatedReads} lecturas adicionales / ${summary.estimatedWrites} escrituras, incluidas estimaciones de auditoría. No es cuota disponible ni ejecución aprobada.")
                Text("Plan ${plan.planId}\nSHA-256 ${plan.fileSha256}", style = MaterialTheme.typography.labelSmall)
                Text(simulation ?: "Sin ejecución simulada importada. El Dry Run no escribe documentos.")
                if (message != null) Text(message!!)
                OutlinedButton(enabled = !busy && sourceUri != null, onClick = {
                    busy = true; message = null
                    scope.launch {
                        try {
                            val admin = IssueReviewAuthorization.verify()
                            val request = withContext(Dispatchers.IO) {
                                val hash = requireNotNull(plan.fileSha256)
                                fun events(directory: String) = IssueReviewStore(File(context.noBackupFilesDir, directory), admin.uid).load(hash).events.map { d ->
                                    mapOf("issueId" to d.issueId, "kind" to d.kind.name, "original" to d.original, "proposed" to d.proposed,
                                        "reason" to d.reason, "reference" to d.evidence, "reviewer" to d.reviewer, "atMillis" to d.atMillis, "state" to d.state.name, "fileSha256" to d.sourceSha256) }
                                PendingPackage(admin.uid, hash, requireNotNull(sourceUri), SyncExecutionContract.json(plan, excel, comparison, admin.uid),
                                    SyncExecutionContract.jsonValue(mapOf("schemaVersion" to 1, "fileSha256" to hash, "sourcePlanId" to plan.planId,
                                        "authorization" to false, "issueDecisions" to events("excel-issue-reviews"), "movementDeclarations" to events("transfer-reconciliation"),
                                        "classification" to ClassificationReviewStore(File(context.noBackupFilesDir, "product-classification"), admin.uid).readManifest(hash))))
                            }
                            pending = request; exporter.launch("clisa-expediente-${request.hash.take(12)}.zip")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message ?: "No se pudo preparar el expediente." }
                        finally { busy = false }
                    }
                }, modifier = Modifier.testTag("final-export")) { Text("Exportar expediente administrativo") }
                OutlinedButton(onClick = { reportPicker.launch(arrayOf("application/json", "application/octet-stream")) }, enabled = !busy) { Text("Cargar resultado de Emulator") }
                Button(onClick = {}, enabled = false, modifier = Modifier.testTag("final-sync")) { Text("Ejecución real bloqueada") }
            } }
            items(summary.reasons) { reason -> FinalCard { Text(reason, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) return out.toByteArray(); require(out.size() + n <= limit); out.write(buffer, 0, n) }
}
@Composable private fun FinalCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color(0xFF03152C), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF07568B))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
