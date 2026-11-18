package com.example.app_clisa.data.backend

import android.content.Context
import android.net.Uri
import com.example.app_clisa.data.excel.*
import kotlinx.coroutines.*
import java.io.File

/** Explicit requests only. Local export/comparison/classification remain independent of Render availability. */
internal class RenderImportRepository(context: Context,
    private val session: BackendSession = FirebaseBackendSession(),
    private val client: ClisaBackendClient = ClisaBackendClient(session)) {
    private val context = context.applicationContext
    suspend fun checkConnection() = withContext(Dispatchers.IO) { client.checkConnection() }
    suspend fun prepare(plan: SyncPlan, excel: ExcelInventory, comparison: InventoryComparison, source: Uri): BackendProposal = withContext(Dispatchers.IO) {
        if (excel.errorCount != 0 || comparison.sourceHasErrors || comparison.inventory?.complete != true)
            throw BackendFailure(BackendProblem.INVALID_SOURCE)
        val uid = session.currentUid() ?: throw BackendFailure(BackendProblem.SESSION)
        val contract = SyncExecutionContract.json(plan, excel, comparison, uid)
        val file = File.createTempFile("clisa-backend-", ".xlsx", context.cacheDir)
        try {
            val cancellation = currentCoroutineContext()
            try {
                requireNotNull(context.contentResolver.openInputStream(source)).use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(8192); var size = 0L
                    while (true) { cancellation.ensureActive(); val n = input.read(buffer); if (n < 0) break
                        size += n; if (size > ClisaBackendClient.MAX_EXCEL_BYTES) throw BackendFailure(BackendProblem.TOO_LARGE)
                        output.write(buffer, 0, n)
                    }
                } }
            } catch (e: CancellationException) { throw e }
            catch (e: BackendFailure) { throw e }
            catch (_: Exception) { throw BackendFailure(BackendProblem.CHANGED_FILE) }
            if (session.currentUid() != uid) throw BackendFailure(BackendProblem.SESSION)
            client.prepare(file, contract, uid)
        } finally { file.delete() }
    }
}
