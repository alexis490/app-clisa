package com.example.app_clisa.ui.products

import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.app_clisa.data.backend.*
import com.example.app_clisa.data.excel.*
import kotlinx.coroutines.*

/** Same summary card/style. No new screen/stage and no automatic network request on navigation. */
@Composable internal fun RenderImportActions(plan: SyncPlan?, excel: ExcelInventory?, comparison: InventoryComparison?, source: Uri?, disabled: Boolean, onlyCheck: Boolean = false) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val repository = remember(context) { RenderImportRepository(context) }
    var work by remember(plan?.planId) { mutableStateOf<Job?>(null) }
    var busy by remember(plan?.planId) { mutableStateOf(false) }
    var message by remember(plan?.planId) { mutableStateOf<String?>(null) }
    DisposableEffect(plan?.planId) { onDispose { work?.cancel() } }
    fun launch(prepare: Boolean) {
        if (busy || disabled) return
        busy = true; message = "Conectando de forma segura. El servicio puede tardar en activarse…"
        work = scope.launch {
            try {
                withTimeout(180_000) {
                    if (prepare) {
                        val result = repository.prepare(requireNotNull(plan), requireNotNull(excel), requireNotNull(comparison), requireNotNull(source))
                        message = "Propuesta preparada: ${result.operations} operaciones para revisión. No se ejecutaron cambios."
                    } else {
                        repository.checkConnection()
                        message = "Conexión y acceso administrativo verificados. Escrituras de inventario deshabilitadas."
                    }
                }
            } catch (_: TimeoutCancellationException) { message = BackendProblem.TIMEOUT.userMessage }
            catch (e: CancellationException) { throw e }
            catch (e: BackendFailure) { message = e.problem.userMessage }
            catch (_: Exception) { message = "No se pudo completar la revisión remota. El expediente local se conserva." }
            finally { busy = false }
        }
    }
    RenderImportButtons(busy, disabled, source != null && plan != null && excel?.errorCount == 0 && comparison?.sourceHasErrors == false, message,
        onCheck = { launch(false) }, onPrepare = { launch(true) }, onCancel = { work?.cancel(); message = "Solicitud cancelada. No se ejecutó una sincronización." }, onlyCheck)
}
@Composable internal fun RenderImportButtons(busy: Boolean, disabled: Boolean, canPrepare: Boolean, message: String?,
    onCheck: () -> Unit, onPrepare: () -> Unit, onCancel: () -> Unit, onlyCheck: Boolean = false) {
    OutlinedButton(onClick = onCheck, enabled = !disabled && !busy, modifier = Modifier.testTag("render-check")) { Text("Comprobar conexión con servidor") }
    if (!onlyCheck) OutlinedButton(onClick = onPrepare, enabled = !disabled && !busy && canPrepare, modifier = Modifier.testTag("render-prepare")) { Text("Preparar propuesta en servidor") }
    if (message != null) Text(message, modifier = Modifier.testTag("render-result"))
    if (busy) TextButton(onClick = onCancel, modifier = Modifier.testTag("render-cancel")) { Text("Cancelar solicitud") }
}
