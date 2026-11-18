package com.example.app_clisa

import com.example.app_clisa.data.backend.*
import com.example.app_clisa.data.excel.SyncPlanIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class AutomaticBackendClientTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var api: ClisaBackendClient
    private val id = "12345678-1234-1234-1234-123456789abc"
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false); server.start()
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        api = ClisaBackendClient(object : BackendSession {
            override fun currentUid() = "ADMIN"
            override suspend fun credential(forceRefresh: Boolean) = BackendCredential("ADMIN", "local-test-token")
        }, server.url("/").toString(), http)
    }
    @After fun stop() { server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    private fun response(value: JSONObject) = server.enqueue(MockResponse().setBody(value.toString()))
    private fun job(status: String = "PREVIEW") = JSONObject().put("id", id).put("status", status).put("total", 1).put("cursor", if (status in setOf("COMPLETED", "PARTIAL")) 1 else 0)
        .put("confirmed", status in setOf("SYNCING", "COMPLETED", "PARTIAL")).put("items", JSONArray()).put("preview", JSONObject()).put("summary", JSONObject().put("syncErrors", 0))
    @Test fun uploadUsesAuthorizedExcelAndNeverSendsConfirmation() = runBlocking {
        val file = File(System.getenv("CLISA_EXCEL_SAMPLE") ?: "C:/Users/TUF/Documents/stock.production.lot123 - 2000.xlsx")
        response(JSONObject().put("status", "ok")); response(JSONObject().put("mode", "AUTOMATIC_IMPORT").put("inventoryWritesEnabled", true))
        response(job("COMPARISON").put("fileSha256", file.inputStream().use { SyncPlanIdentity.fileSha256(it) }))
        val result = api.uploadAutomatic(file, file.name)
        assertEquals("COMPARISON", result.getString("status")); assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals("/v1/admin/status", server.takeRequest().path)
        val request = server.takeRequest(); assertEquals("/v1/admin/excel-imports", request.path); assertEquals("Bearer local-test-token", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains(file.name)); assertTrue(body.contains("\"reviewBeforeClassification\":true")); assertEquals(3, server.requestCount)
    }
    @Test fun previewAndConfirmationUseSeparateProtectedRequests() = runBlocking {
        response(job()); api.automaticJob(id)
        assertEquals("/v1/admin/excel-imports/$id?offset=0", server.takeRequest().path)
        response(job("SYNCING")); api.automaticJob(id, "confirm")
        val confirm = server.takeRequest(); assertEquals("POST", confirm.method); assertEquals("/v1/admin/excel-imports/$id/confirm", confirm.path)
    }
    @Test fun incompleteOrFailedImportCannotBePresentedAsCompleted() = runBlocking {
        for (bad in listOf(job("COMPLETED").put("cursor", 0), job("COMPLETED").put("summary", JSONObject().put("syncErrors", 1)), job().put("id", "other"))) {
            response(bad)
            try { api.automaticJob(id); fail("Debe rechazar resultados inconsistentes") }
            catch (e: BackendFailure) { assertEquals(BackendProblem.UNSAFE_RESPONSE, e.problem) }
        }
    }
    @Test fun partialResultIsRetainedAsPartial() = runBlocking {
        response(job("PARTIAL").put("summary", JSONObject().put("syncErrors", 1)))
        assertEquals("PARTIAL", api.automaticJob(id).getString("status"))
    }
    @Test fun completedAnalysisCanStopAtReviewWithoutConfirmation() = runBlocking {
        response(job("REVIEW_READY").put("cursor", 1).put("confirmed", false))
        assertEquals("REVIEW_READY", api.automaticJob(id).getString("status"))
        for (bad in listOf(job("REVIEW_READY").put("cursor", 0), job("REVIEW_READY").put("cursor", 1).put("confirmed", true))) {
            response(bad)
            try { api.automaticJob(id); fail("Revisión prematura o confirmada") }
            catch (e: BackendFailure) { assertEquals(BackendProblem.UNSAFE_RESPONSE, e.problem) }
        }
    }
    @Test fun formatInspectionUsesItsOwnEndpointAndNeverCreatesAnImport() = runBlocking {
        val file = File("C:/Users/TUF/Documents/stock.production.lot123.xlsx")
        val checks = JSONObject().put("serial", true).put("code", true).put("name", true).put("quantity", false)
        response(JSONObject().put("status", "ok"))
        response(JSONObject().put("status", "FORMAT_CHECKED").put("checks", checks).put("ready", false)
            .put("fileSha256", file.inputStream().use(SyncPlanIdentity::fileSha256)))
        val result = api.inspectExcelFormat(file, file.name)
        assertFalse(result.ready); assertEquals(setOf("serial", "code", "name"), result.detected)
        assertEquals("/health", server.takeRequest().path)
        assertEquals("/v1/admin/excel-format", server.takeRequest().path); assertEquals(2, server.requestCount)
    }
    @Test fun formatCannotUnlockAnalysisWithAnUnrelatedFileHash() = runBlocking {
        val file = File("C:/Users/TUF/Documents/stock.production.lot123.xlsx")
        response(JSONObject().put("status", "ok"))
        response(JSONObject().put("status", "FORMAT_CHECKED").put("checks", JSONObject().put("serial", true).put("code", true).put("name", true).put("quantity", true)).put("ready", true).put("fileSha256", "other"))
        try { api.inspectExcelFormat(file, file.name); fail("Debe rechazar otra huella") }
        catch (e: BackendFailure) { assertEquals(BackendProblem.UNSAFE_RESPONSE, e.problem) }
    }
    @Test fun uploadTimeoutNamesTheStageAndNeverRepeatsPost() = runBlocking {
        val file = File("C:/Users/TUF/Documents/stock.production.lot123.xlsx")
        response(JSONObject().put("status", "ok")); response(JSONObject().put("mode", "AUTOMATIC_IMPORT").put("inventoryWritesEnabled", true))
        server.enqueue(MockResponse().setResponseCode(504))
        val progress = mutableListOf<String>()
        try { api.uploadAutomatic(file, file.name, onProgress = { progress += it }); fail("Debe informar el timeout") }
        catch (e: BackendFailure) { assertEquals(BackendProblem.TIMEOUT, e.problem); assertTrue(e.message!!.startsWith("Carga del Excel:")) }
        assertEquals(3, progress.size); assertEquals(3, server.requestCount)
        server.takeRequest(); server.takeRequest(); assertEquals("POST", server.takeRequest().method)
    }
    @Test fun connectionTimeoutStatesThatTheExcelWasNotSent() = runBlocking {
        val file = File("C:/Users/TUF/Documents/stock.production.lot123.xlsx")
        repeat(2) { server.enqueue(MockResponse().setResponseCode(504)) }
        try { api.uploadAutomatic(file, file.name); fail("Debe informar el timeout") }
        catch (e: BackendFailure) { assertEquals(BackendProblem.TIMEOUT, e.problem); assertTrue(e.message!!.contains("El Excel todavía no se envió")) }
        assertEquals(2, server.requestCount)
        repeat(2) { assertEquals("/health", server.takeRequest().path) }
    }
    @Test fun historyIsAcceptedOnlyForSameImportAndAuthenticatedOwner() = runBlocking {
        val history=JSONObject().put("idImportacion",id).put("archivo","stock.xlsx").put("fecha","2026-11-01T18:30:00.000Z")
            .put("usuarioUid","ADMIN").put("usuario","admin@example.test").put("destino","Firebase Firestore").put("estado","COMPLETADO")
        response(job("COMPLETED").put("filename","stock.xlsx").put("history",history))
        assertEquals("admin@example.test",api.automaticJob(id).getJSONObject("history").getString("usuario"))
        for((key,value) in listOf("usuarioUid" to "OTHER","idImportacion" to "different","fecha" to "not-a-date","destino" to "Other")){
            response(job("COMPLETED").put("filename","stock.xlsx").put("history",JSONObject(history.toString()).put(key,value)))
            try { api.automaticJob(id);fail("Debe rechazar historial ajeno o inválido") }catch(e:BackendFailure){assertEquals(BackendProblem.UNSAFE_RESPONSE,e.problem)}
        }
    }
    @Test fun uploadRetryKeepsOperationIdAndRejectsAnotherReturnedId() = runBlocking {
        val operationId="e8244195-b626-46b6-8d64-7df84b44f6e3"
        val file=File("C:/Users/TUF/Documents/stock.production.lot123.xlsx")
        repeat(2){
            response(JSONObject().put("status","ok"));response(JSONObject().put("mode","AUTOMATIC_IMPORT").put("inventoryWritesEnabled",true))
            response(job("COMPARISON").put("id",operationId).put("fileSha256",file.inputStream().use(SyncPlanIdentity::fileSha256)))
            assertEquals(operationId,api.uploadAutomatic(file,file.name,importId=operationId).getString("id"))
            server.takeRequest();server.takeRequest();assertTrue(server.takeRequest().body.readUtf8().contains(operationId))
        }
        response(JSONObject().put("status","ok"));response(JSONObject().put("mode","AUTOMATIC_IMPORT").put("inventoryWritesEnabled",true))
        response(job("COMPARISON").put("fileSha256",file.inputStream().use(SyncPlanIdentity::fileSha256)))
        try { api.uploadAutomatic(file,file.name,importId=operationId);fail("Debe rechazar otro expediente") }catch(e:BackendFailure){assertEquals(BackendProblem.UNSAFE_RESPONSE,e.problem)}
    }

}
