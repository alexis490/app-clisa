package com.example.app_clisa.data.backend

import com.example.app_clisa.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class BackendProblem(val userMessage: String) {
    SESSION("Tu sesión necesita verificarse de nuevo. Vuelve a iniciar sesión."),
    FORBIDDEN("Tu cuenta no tiene autorización o está bloqueada para esta operación."),
    NETWORK("No se pudo conectar. Comprueba tu conexión y vuelve a intentarlo."),
    TIMEOUT("Se agotó el tiempo de espera del servidor. No se confirmó la sincronización."),
    RATE_LIMIT("Se alcanzó el límite de solicitudes. Espera un minuto antes de reintentar."),
    BUSY("El servidor sigue procesando la solicitud anterior. Espera unos segundos y reanuda el proceso."),
    TOO_LARGE("El archivo, contrato o respuesta supera el tamaño permitido."),
    INVALID_SOURCE("No se pudo preparar la propuesta. Revisa las incidencias del Excel y vuelve a comparar."),
    UNAVAILABLE("El servicio no está disponible o está ocupado. Inténtalo en unos momentos."),
    UNSAFE_RESPONSE("El servidor devolvió una respuesta que la app no pudo validar. No se confirmó la sincronización."),
    CHANGED_FILE("El Excel cambió. Selecciónalo y compáralo de nuevo.")
}
internal class BackendFailure(val problem: BackendProblem, detail: String? = null) : Exception(detail ?: problem.userMessage)
internal data class BackendProposal(val operations: Int, val sourcePlanId: String)
internal data class ExcelFormatCheck(val detected: Set<String>, val ready: Boolean)

/** No write/execution methods. Tokens are memory-only, bound to a UID and never redirected or logged. */
internal class ClisaBackendClient(
    private val session: BackendSession,
    baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(65, TimeUnit.SECONDS).build()
) {
    val currentUid: String? get() = session.currentUid()
    private val base = baseUrl.toHttpUrl().also { require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null) }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val waking = http.newBuilder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(110, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    private fun sameUser(uid: String) { if (session.currentUid() != uid) throw BackendFailure(BackendProblem.SESSION) }
    suspend fun health() {
        repeat(2) { attempt ->
            try {
                val json = try { response(waking, Request.Builder().url(base.newBuilder().addPathSegment("health").build()).get().build(), 16 * 1024) }
                    catch (e: BackendHttpFailure) { throw BackendFailure(problemFor(e.status)) }
                if (json.optString("status") != "ok") throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
                return
            } catch (e: BackendFailure) {
                if (attempt == 1 || e.problem !in transientProblems) throw e
                delay(1500)
            }
        }
    }
    suspend fun checkConnection(): String {
        // Warm-up contains no credentials and is explicit, never a background listener/poll.
        val uid = session.currentUid() ?: throw BackendFailure(BackendProblem.SESSION)
        health(); sameUser(uid); status(uid, allowAutomatic = true); return uid
    }
    private suspend fun protected(path: String, uid: String, body: RequestBody? = null): JSONObject {
        var refresh = false
        var retriedRead = false
        while (true) {
            sameUser(uid); val credential = session.credential(refresh)
            if (credential.uid != uid) throw BackendFailure(BackendProblem.SESSION)
            sameUser(uid)
            val builder = Request.Builder().url(base.resolve(path)!!).header("Authorization", "Bearer ${credential.token}")
            val request = if (body == null) builder.get().build() else builder.post(body).build()
            try {
                val result = response(http, request, MAX_CONTRACT_BYTES)
                sameUser(uid); return result
            } catch (e: BackendHttpFailure) {
                sameUser(uid)
                if (e.status == 401 && !refresh) { refresh = true; continue }
                val problem = problemFor(e.status)
                if (body == null && !retriedRead && problem in transientProblems) { retriedRead = true; delay(1500); continue }
                throw BackendFailure(problem)
            } catch (e: BackendFailure) {
                sameUser(uid)
                if (body == null && !retriedRead && e.problem in transientProblems) { retriedRead = true; delay(1500); continue }
                throw e
            }
        }
    }
    private suspend fun status(uid: String, allowAutomatic: Boolean = false) {
        val json = protected("v1/admin/status", uid)
        if (allowAutomatic && json.optString("mode") == "AUTOMATIC_IMPORT" && json.opt("inventoryWritesEnabled") == true &&
            json.optJSONArray("capabilities")?.let { a -> (0 until a.length()).any { a.optString(it) == "automatic-import" } } == true) return
        if (json.optString("mode") != "SAFE_PREVIEW" || json.opt("inventoryWritesEnabled") != false ||
            json.optJSONArray("capabilities")?.let { a -> (0 until a.length()).any { a.optString(it) == "prepare-proposal" } } != true)
            throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
    }
    suspend fun inspectExcelFormat(excel: File, filename: String): ExcelFormatCheck {
        val uid = session.currentUid() ?: throw BackendFailure(BackendProblem.SESSION)
        if (excel.length() !in 1..MAX_EXCEL_BYTES) throw BackendFailure(BackendProblem.TOO_LARGE)
        health(); sameUser(uid)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("contract", "options.json", "{}".toRequestBody("application/json".toMediaType()))
            .addFormDataPart("excel", filename, excel.asRequestBody("application/octet-stream".toMediaType())).build()
        val value = protected("v1/admin/excel-format", uid, body)
        val checks = value.optJSONObject("checks") ?: throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        val keys = listOf("serial", "code", "name", "quantity")
        if (value.optString("status") != "FORMAT_CHECKED" || keys.any { checks.opt(it) !is Boolean } || value.opt("ready") !is Boolean ||
            value.optString("fileSha256") != excel.inputStream().use { com.example.app_clisa.data.excel.SyncPlanIdentity.fileSha256(it) })
            throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        val found = keys.filter { checks.getBoolean(it) }.toSet()
        if (value.getBoolean("ready") != (found.size == keys.size)) throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        return ExcelFormatCheck(found, found.size == keys.size)
    }
    suspend fun uploadAutomatic(excel: File, filename: String, mappings: JSONObject = JSONObject(), importId: String? = null, onProgress: (String) -> Unit = {}): JSONObject {
        if (importId != null) require(importId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        val uid = session.currentUid() ?: throw BackendFailure(BackendProblem.SESSION)
        if (excel.length() !in 1..MAX_EXCEL_BYTES) throw BackendFailure(BackendProblem.TOO_LARGE)
        onProgress("Conectando con el servidor. El primer inicio puede tardar; espera sin volver a seleccionar el archivo…")
        try { health() } catch (e: BackendFailure) {
            if (e.problem == BackendProblem.TIMEOUT) throw BackendFailure(e.problem, "Conexión: el servidor no respondió dentro del tiempo de espera. El Excel todavía no se envió.")
            throw e
        }
        sameUser(uid)
        onProgress("Verificando tu autorización para importar…")
        val status = try { protected("v1/admin/status", uid) } catch (e: BackendFailure) {
            if (e.problem == BackendProblem.TIMEOUT) throw BackendFailure(e.problem, "Autorización: el servidor no respondió a tiempo. El Excel todavía no se envió.")
            throw e
        }
        check(status.optString("mode") == "AUTOMATIC_IMPORT" && status.optBoolean("inventoryWritesEnabled")) {
            "El servicio de importación aún no tiene disponible esta actualización. Contacta al administrador para habilitarla."
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("contract", "options.json", JSONObject().put("mappings", mappings).put("reviewBeforeClassification", true).apply { if (importId != null) put("importId", importId) }.toString().toRequestBody("application/json".toMediaType()))
            .addFormDataPart("excel", filename, excel.asRequestBody("application/octet-stream".toMediaType())).build()
        onProgress("Enviando el Excel y esperando el análisis inicial…")
        val result = try { protected("v1/admin/excel-imports", uid, body) }
            catch (e: BackendFailure) {
                if (e.problem == BackendProblem.UNSAFE_RESPONSE) throw BackendFailure(e.problem, "Carga del Excel: el servidor devolvió una respuesta inválida. No se confirmó la sincronización.")
                if (e.problem == BackendProblem.TIMEOUT) throw BackendFailure(e.problem, "Carga del Excel: se envió la solicitud, pero no llegó el resultado a tiempo. No se confirmó la sincronización.")
                throw e
            }
        if (result.optString("status") != "MAPPING_REQUIRED") {
            try { validateAutomaticJob(result) }
            catch (e: BackendFailure) { throw BackendFailure(e.problem, "Carga del Excel: la respuesta no contiene un análisis válido. No se confirmó la sincronización.") }
            if (importId != null && result.optString("id") != importId) throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
            if (result.optString("fileSha256") != excel.inputStream().use { com.example.app_clisa.data.excel.SyncPlanIdentity.fileSha256(it) })
                throw BackendFailure(BackendProblem.UNSAFE_RESPONSE, "Carga del Excel: la huella recibida no coincide con el archivo seleccionado. Selecciónalo de nuevo.")
        }
        return result
    }
    suspend fun automaticJob(id: String, action: String? = null, offset: Int = 0): JSONObject {
        require(id.matches(Regex("[0-9a-f-]{36}")) && action in setOf(null, "advance", "review", "classify", "confirm") && offset >= 0)
        val uid = session.currentUid() ?: throw BackendFailure(BackendProblem.SESSION)
        val path = "v1/admin/excel-imports/$id" + (action?.let { "/$it" } ?: "?offset=$offset")
        val result = protected(path, uid, action?.let { ByteArray(0).toRequestBody(null) })
        validateAutomaticJob(result)
        if (result.optString("id") != id) throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        return result
    }
    suspend fun importExportPage(id: String, section: String, offset: Int): JSONObject {
        require(id.matches(Regex("[0-9a-f-]{36}")) && section in setOf("products", "issues") && offset in 0..5000)
        val uid=session.currentUid() ?: throw BackendFailure(BackendProblem.SESSION)
        val page=protected("v1/admin/excel-imports/$id/export?section=$section&offset=$offset",uid)
        val metadata=page.optJSONObject("job") ?: throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        validateAutomaticJob(metadata)
        val data=page.optJSONObject("data") ?: throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        if(page.optInt("schemaVersion")!=1 || metadata.optString("id")!=id || metadata.optString("status") !in setOf("COMPLETED","PARTIAL") ||
            page.optString("section")!=section || page.optInt("offset",-1)!=offset || page.opt("complete") !is Boolean ||
            !page.has("nextOffset") || (!page.isNull("nextOffset") && page.optInt("nextOffset",-1) !in (offset+1)..(if(section=="products")metadata.optInt("total") else 1000)) ||
            listOf("products","operations","issues").any {data.optJSONArray(it)==null} ||
            data.getJSONArray("products").length()>50 || data.getJSONArray("operations").length()>100050 || data.getJSONArray("issues").length()>20000)
            throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        return page
    }
    private fun validateAutomaticJob(value: JSONObject) {
        val state = value.optString("status")
        val total = value.optInt("total", -1)
        val cursor = value.optInt("cursor", -1)
        if (!value.optString("id").matches(Regex("[0-9a-f-]{36}")) || state !in setOf("COMPARISON", "REVIEW_READY", "CLASSIFICATION", "PREVIEW", "SYNCING", "COMPLETED", "PARTIAL") ||
            total !in 1..5000 || cursor !in 0..total || value.optJSONArray("items") == null ||
            value.optJSONObject("summary") == null || value.optJSONObject("preview") == null ||
            (state in setOf("COMPLETED", "PARTIAL") && (cursor != total || !value.optBoolean("confirmed"))) ||
            (state == "REVIEW_READY" && (cursor != total || value.optBoolean("confirmed"))) ||
            (state == "COMPLETED" && value.getJSONObject("summary").optInt("syncErrors", -1) != 0))
            throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        value.optJSONObject("analysis")?.let { a ->
            val bounded = mapOf("rowsRead" to 250000, "validProducts" to total, "inventoryTarget" to total,
                "inventoryChecked" to a.optInt("inventoryTarget", -1), "productsCompared" to total, "seriesTotal" to 250000,
                "seriesProcessed" to a.optInt("seriesTotal", -1), "conflictsChecked" to total, "excludedProducts" to total)
            if (bounded.any { (key, max) -> a.optInt(key, -1) !in 0..max }) throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        }
        value.optJSONObject("history")?.let { h ->
            if (state !in setOf("COMPLETED", "PARTIAL") || h.optString("idImportacion") != value.optString("id") ||
                h.optString("usuarioUid") != session.currentUid() || h.optString("archivo") != value.optString("filename") ||
                h.optString("destino") != "Firebase Firestore" || h.optString("estado") !in setOf("COMPLETADO", "COMPLETADO_CON_OBSERVACIONES", "FALLIDO") ||
                !h.optString("usuario").matches(Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")) ||
                runCatching { java.time.Instant.parse(h.getString("fecha")) }.isFailure)
                throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        }
        value.optJSONObject("classificationProgress")?.let { c ->
            if (listOf("processed", "byRules", "byAi", "pending", "excluded", "rulesProcessed", "aiProcessed").any { c.optInt(it, -1) !in 0..total } ||
                c.optInt("byRules") + c.optInt("byAi") + c.optInt("pending") != c.optInt("processed") ||
                c.optInt("rulesProcessed") + c.optInt("aiProcessed") != c.optInt("processed") ||
                c.optInt("processed") + c.optInt("excluded") > total)
                throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        }
        value.optJSONObject("review")?.let { r ->
            val q = r.optJSONObject("quality") ?: throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
            val c = r.optJSONObject("comparison") ?: throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
            val v = r.optJSONObject("validation") ?: throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
            if (r.optInt("rowsProcessed", -1) != value.optInt("rowCount", -2) ||
                listOf("uniqueProducts", "newProducts", "validProducts").any { r.optInt(it, -1) !in 0..total } ||
                r.optInt("issueCount", -1) !in 0..20000 ||
                listOf("duplicateCodes", "duplicateSeries", "emptySeries", "negativeQuantities", "otherErrors").any { q.optInt(it, -1) !in 0..20000 } ||
                listOf("unchanged", "increased", "decreased", "newProducts", "unknownStock").any { c.optInt(it, -1) !in 0..total } ||
                (!c.isNull("absent") && c.optInt("absent", -1) !in 0..5000) ||
                listOf("validRecords", "separatedRecords").any { !v.isNull(it) && v.optInt(it, -1) !in 0..value.optInt("rowCount") })
                throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        }
    }
    suspend fun prepare(excel: File, contract: String, uid: String): BackendProposal {
        sameUser(uid)
        if (excel.length() !in 1..MAX_EXCEL_BYTES || contract.toByteArray(Charsets.UTF_8).size > MAX_CONTRACT_BYTES) throw BackendFailure(BackendProblem.TOO_LARGE)
        val original = parse(contract)
        val plan = original.optJSONObject("plan") ?: throw BackendFailure(BackendProblem.INVALID_SOURCE)
        if (original.optJSONObject("requester")?.optString("uid") != uid || plan.opt("productionExecutable") != false ||
            plan.optString("inventoryPolicy") != "EXCEL_REFERENCE_V1") throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        val expectedHash = plan.optString("fileSha256")
        if (excel.inputStream().use { com.example.app_clisa.data.excel.SyncPlanIdentity.fileSha256(it) } != expectedHash) throw BackendFailure(BackendProblem.CHANGED_FILE)
        health(); sameUser(uid); status(uid) // Authorize before sending any Excel/contract bytes.
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("contract", "contract.json", contract.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addFormDataPart("excel", "source.xlsx", excel.asRequestBody("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".toMediaType())).build()
        val json = protected("v1/admin/imports/prepare", uid, body)
        val proposal = json.optJSONObject("proposal"); val remote = proposal?.optJSONObject("plan")
        if (json.optString("mode") != "SAFE_PREVIEW" || json.opt("inventoryWritesEnabled") != false || json.opt("authorization") != false ||
            remote?.opt("productionExecutable") != false || remote.optInt("schemaVersion") != 4 || remote.optString("inventoryPolicy") != "EXCEL_REFERENCE_V1" ||
            remote.optString("fileSha256") != expectedHash || remote.optString("baselineFingerprint") != plan.optString("baselineFingerprint") ||
            proposal.optJSONObject("requester")?.optString("uid") != uid || proposal.optJSONObject("preparation")?.optString("originalPlanId") != plan.optString("planId"))
            throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        val operations = remote.optJSONArray("operations") ?: throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        if (operations.length() > 30_000 || (0 until operations.length()).any { operations.optJSONObject(it)?.optString("type") !in setOf("CREATE_PRODUCT", "CREATE_EXISTENCE", "UPDATE_EXISTENCE", "UPDATE_PRODUCT_STOCK", "SKIP_UNCHANGED", "REVIEW_MISSING_PRODUCT", "REVIEW_MISSING_EXISTENCE") })
            throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
        return BackendProposal(operations.length(), plan.getString("planId"))
    }
    private suspend fun response(client: OkHttpClient, request: Request, limit: Int): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        fun failure(error: Exception) {
            val mapped = when (error) {
                is BackendHttpFailure, is BackendFailure -> error
                is SocketTimeoutException, is java.io.InterruptedIOException -> BackendFailure(BackendProblem.TIMEOUT)
                is IOException -> BackendFailure(BackendProblem.NETWORK)
                else -> BackendFailure(BackendProblem.UNSAFE_RESPONSE)
            }
            if (continuation.isActive) continuation.resumeWithException(mapped)
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = failure(e)
            override fun onResponse(call: Call, response: Response) {
                try { response.use { r ->
                    if (!r.isSuccessful) throw BackendHttpFailure(r.code)
                    val body = r.body ?: throw BackendFailure(BackendProblem.UNSAFE_RESPONSE)
                    if (body.contentLength() > limit) throw BackendFailure(BackendProblem.TOO_LARGE)
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input -> val buffer = ByteArray(8192); while (true) {
                        if (!continuation.isActive) return
                        val n = input.read(buffer); if (n < 0) break
                        if (output.size() + n > limit) throw BackendFailure(BackendProblem.TOO_LARGE)
                        output.write(buffer, 0, n)
                    } }
                    val result = parse(output.toString(Charsets.UTF_8.name()))
                    if (continuation.isActive) continuation.resume(result)
                } } catch (e: Exception) { failure(e) }
            }
        })
    }
    private fun parse(text: String) = try { JSONObject(text) } catch (_: Exception) { throw BackendFailure(BackendProblem.UNSAFE_RESPONSE) }
    private class BackendHttpFailure(val status: Int) : Exception()
    companion object {
        private val transientProblems = setOf(BackendProblem.TIMEOUT, BackendProblem.UNAVAILABLE, BackendProblem.NETWORK)
        const val MAX_EXCEL_BYTES = 25L * 1024 * 1024
        const val MAX_CONTRACT_BYTES = 8 * 1024 * 1024
        fun problemFor(status: Int): BackendProblem = when (status) {
            401 -> BackendProblem.SESSION; 403 -> BackendProblem.FORBIDDEN; 413 -> BackendProblem.TOO_LARGE
            400, 415, 422 -> BackendProblem.INVALID_SOURCE; 429 -> BackendProblem.RATE_LIMIT
            408, 504 -> BackendProblem.TIMEOUT; 503 -> BackendProblem.UNAVAILABLE; 409 -> BackendProblem.BUSY
            else -> BackendProblem.UNSAFE_RESPONSE
        }
    }
}
