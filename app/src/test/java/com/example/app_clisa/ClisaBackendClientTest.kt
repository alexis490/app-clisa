package com.example.app_clisa

import com.example.app_clisa.data.backend.*
import com.example.app_clisa.data.excel.SyncPlanIdentity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ClisaBackendClientTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private class Session : BackendSession {
        var uid: String? = "ADMIN"; val refreshes = mutableListOf<Boolean>(); var deny = false; var changeOnRefresh = false
        override fun currentUid() = uid
        override suspend fun credential(forceRefresh: Boolean): BackendCredential {
            refreshes += forceRefresh
            if (deny) throw BackendFailure(BackendProblem.FORBIDDEN)
            return BackendCredential(if (forceRefresh && changeOnRefresh) "OTHER" else uid!!, if (forceRefresh) "fresh-token" else "cached-token")
        }
    }
    private lateinit var session: Session
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false); server.start()
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        session = Session()
    }
    @After fun stop() { server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    private fun client(custom: OkHttpClient = http) = ClisaBackendClient(session, server.url("/").toString(), custom)
    private fun health() = server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
    private fun status(writes: Boolean = false) = server.enqueue(MockResponse().setBody("""{"mode":"SAFE_PREVIEW","inventoryWritesEnabled":$writes,"capabilities":["status","prepare-proposal"]}"""))
    private fun rejects(problem: BackendProblem, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("Debía rechazar") } catch (e: BackendFailure) { assertEquals(problem, e.problem) }
    }
    @Test fun onlyHttpsRootWithoutUserInfoOrQueryIsAccepted() {
        for (url in listOf("http://example.org/", "https://user:password@example.org/", "https://example.org/path", "https://example.org/?token=x")) {
            try { ClisaBackendClient(session, url, http); fail("URL insegura") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun healthUsesNoTokenAndStatusUsesFirebaseBearer() = runBlocking {
        health(); status(); assertEquals("ADMIN", client().checkConnection())
        val wake = server.takeRequest(); assertEquals("/health", wake.path); assertNull(wake.getHeader("Authorization"))
        val protected = server.takeRequest(); assertEquals("/v1/admin/status", protected.path); assertEquals("Bearer cached-token", protected.getHeader("Authorization"))
    }
    @Test fun connectionAcceptsEnabledAutomaticImportWithoutConfirmingAnything() = runBlocking {
        health(); server.enqueue(MockResponse().setBody("""{"mode":"AUTOMATIC_IMPORT","inventoryWritesEnabled":true,"capabilities":["status","prepare-proposal","automatic-import"]}"""))
        assertEquals("ADMIN", client().checkConnection())
        server.takeRequest(); assertEquals("/v1/admin/status", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }
    @Test fun legacyPrepareStillRejectsAutomaticWritesBeforeUploadingExcel() {
        val (file, contract) = source(); health()
        server.enqueue(MockResponse().setBody("""{"mode":"AUTOMATIC_IMPORT","inventoryWritesEnabled":true,"capabilities":["prepare-proposal","automatic-import"]}"""))
        rejects(BackendProblem.UNSAFE_RESPONSE) { client().prepare(file, contract, "ADMIN") }
        assertEquals(2, server.requestCount)
    }
    @Test fun unauthorizedRefreshesFirebaseTokenOnlyOnce() = runBlocking {
        health(); server.enqueue(MockResponse().setResponseCode(401)); status()
        client().checkConnection(); assertEquals(listOf(false, true), session.refreshes)
        server.takeRequest(); server.takeRequest(); assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
    }
    @Test fun repeated401RequiresReauthenticationAndDoesNotLoop() {
        health(); repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
        rejects(BackendProblem.SESSION) { client().checkConnection() }; assertEquals(3, server.requestCount)
    }
    @Test fun forbiddenDoesNotRefreshOrRetry() {
        health(); server.enqueue(MockResponse().setResponseCode(403).setBody("PRIVATE INTERNAL DETAILS"))
        rejects(BackendProblem.FORBIDDEN) { client().checkConnection() }; assertEquals(listOf(false), session.refreshes)
        assertFalse(BackendProblem.FORBIDDEN.userMessage.contains("PRIVATE"))
    }
    @Test fun userOrIncompleteClaimsCannotGetAdministrativeCredentials() {
        for (claims in listOf(emptyMap(), mapOf("role" to "USER"), mapOf("role" to "ADMIN", "admin" to "true"), mapOf("role" to "SUPER_ADMIN", "admin" to true))) {
            rejects(BackendProblem.FORBIDDEN) { BackendRolePolicy.requireAdmin(claims) }
        }
        BackendRolePolicy.requireAdmin(mapOf("role" to "ADMIN", "admin" to true))
        BackendRolePolicy.requireAdmin(mapOf("role" to "SUPER_ADMIN", "admin" to true, "superAdmin" to true))
        session.deny = true; health(); rejects(BackendProblem.FORBIDDEN) { client().checkConnection() }; assertEquals(1, server.requestCount)
    }
    @Test fun redirectsCannotForwardCredentials() {
        health(); server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/credential-leak")))
        rejects(BackendProblem.UNSAFE_RESPONSE) { client().checkConnection() }; assertEquals(2, server.requestCount)
    }
    @Test fun logoutDuringWarmupDiscardsResultWithoutSendingToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val work = async { try { client().checkConnection(); null } catch (e: BackendFailure) { e.problem } }
        withContext(Dispatchers.IO) { server.takeRequest() }; session.uid = null
        assertEquals(BackendProblem.SESSION, work.await()); assertTrue(session.refreshes.isEmpty())
    }
    @Test fun changingUidDuringRefreshCannotReplayAnotherUsersRequest() {
        health(); server.enqueue(MockResponse().setResponseCode(401)); session.changeOnRefresh = true
        rejects(BackendProblem.SESSION) { client().checkConnection() }; assertEquals(2, server.requestCount)
    }
    private fun source(): Pair<java.io.File, String> {
        val file = temp.newFile("source.xlsx"); file.writeBytes(byteArrayOf(0x50, 0x4b, 3, 4, 1, 2))
        val hash = file.inputStream().use(SyncPlanIdentity::fileSha256)
        val c = JSONObject().put("requester", JSONObject().put("uid", "ADMIN")).put("plan", JSONObject()
            .put("productionExecutable", false).put("inventoryPolicy", "EXCEL_REFERENCE_V1").put("fileSha256", hash)
            .put("baselineFingerprint", "baseline").put("planId", "local-plan"))
        return file to c.toString()
    }
    private fun proposal(contract: String, authorization: Boolean = false, hashOverride: String? = null): String {
        val c = JSONObject(contract); val p = c.getJSONObject("plan")
        return JSONObject().put("mode", "SAFE_PREVIEW").put("inventoryWritesEnabled", false).put("authorization", authorization)
            .put("proposal", JSONObject().put("requester", c.getJSONObject("requester"))
                .put("preparation", JSONObject().put("originalPlanId", p.getString("planId")))
                .put("plan", JSONObject(p.toString()).put("schemaVersion", 4).put("fileSha256", hashOverride ?: p.getString("fileSha256"))
                    .put("operations", org.json.JSONArray().put(JSONObject().put("type", "UPDATE_PRODUCT_STOCK"))))).toString()
    }
    @Test fun prepareUsesExistingEndpointAndExactMultipartPartsAfterStatus() = runBlocking {
        val (file, contract) = source(); health(); status(); server.enqueue(MockResponse().setBody(proposal(contract)))
        val result = client().prepare(file, contract, "ADMIN"); assertEquals(1, result.operations)
        server.takeRequest(); assertEquals("/v1/admin/status", server.takeRequest().path)
        val upload = server.takeRequest(); assertEquals("/v1/admin/imports/prepare", upload.path)
        assertEquals("POST", upload.method); assertEquals("Bearer cached-token", upload.getHeader("Authorization"))
        val body = upload.body.readUtf8(); assertTrue(body.contains("name=\"contract\"; filename=\"contract.json\""))
        assertTrue(body.contains("name=\"excel\"; filename=\"source.xlsx\"")); assertFalse(body.contains("cached-token"))
    }
    @Test fun emitsAndroidMultipartForTheExistingNodeEmulatorApi() = runBlocking<Unit> {
        val roots = listOf(java.io.File("../docs/importacion-paso4b-final/fixtures"), java.io.File("docs/importacion-paso4b-final/fixtures"))
        val root = roots.first { java.io.File(it, "valid.json").exists() }
        val c = JSONObject(java.io.File(root, "valid.json").readText())
        session.uid = "HTTP_ADMIN"; c.getJSONObject("requester").put("uid", "HTTP_ADMIN")
        val file = java.io.File(root, "valid.xlsx"); health(); status(); server.enqueue(MockResponse().setBody(proposal(c.toString())))
        client().prepare(file, c.toString(), "HTTP_ADMIN")
        server.takeRequest(); server.takeRequest(); val upload = server.takeRequest()
        System.getenv("CLISA_ANDROID_MULTIPART_FIXTURE")?.let { path ->
            java.io.File(path).writeText(JSONObject().put("contentType", upload.getHeader("Content-Type"))
                .put("bodyBase64", java.util.Base64.getEncoder().encodeToString(upload.body.readByteArray()))
                .put("fileSha256", c.getJSONObject("plan").getString("fileSha256"))
                .put("originalPlanId", c.getJSONObject("plan").getString("planId")).toString())
        }
    }
    @Test fun statusWithWritesEnabledCannotReachUpload() {
        val (file, c) = source(); health(); status(true)
        rejects(BackendProblem.UNSAFE_RESPONSE) { client().prepare(file, c, "ADMIN") }; assertEquals(2, server.requestCount)
    }
    @Test fun remoteProposalCannotAuthorizeWritesOrChangeTheSource() {
        for (invalid in listOf(true, false)) {
            val (file, c) = sourceWithUniqueName(); health(); status()
            server.enqueue(MockResponse().setBody(proposal(c, invalid, if (invalid) null else "other-file")))
            rejects(BackendProblem.UNSAFE_RESPONSE) { client().prepare(file, c, "ADMIN") }
        }
    }
    private fun sourceWithUniqueName(): Pair<java.io.File, String> { temp.root.listFiles()?.filter { it.name == "source.xlsx" }?.forEach { it.delete() }; return source() }
    @Test fun changedExcelIsRejectedBeforeNetwork() {
        val (file, c) = source(); file.appendText("changed")
        rejects(BackendProblem.CHANGED_FILE) { client().prepare(file, c, "ADMIN") }; assertEquals(0, server.requestCount)
    }
    @Test fun rateLimitAndOtherStatusesAreMappedWithoutAutomaticPostRetry() {
        for ((status, problem) in listOf(429 to BackendProblem.RATE_LIMIT, 413 to BackendProblem.TOO_LARGE, 422 to BackendProblem.INVALID_SOURCE,
            503 to BackendProblem.UNAVAILABLE, 504 to BackendProblem.TIMEOUT)) {
            health(); server.enqueue(MockResponse().setResponseCode(status))
            if (status in setOf(503, 504)) server.enqueue(MockResponse().setResponseCode(status))
            rejects(problem) { client().checkConnection() }
        }
        assertEquals(12, server.requestCount)
    }
    @Test fun excessiveResponseAndInvalidJsonFailClosed() {
        server.enqueue(MockResponse().setBody("x".repeat(17 * 1024))); rejects(BackendProblem.TOO_LARGE) { client().health() }
        server.enqueue(MockResponse().setBody("not JSON")); rejects(BackendProblem.UNSAFE_RESPONSE) { client().health() }
    }
    @Test fun requestCancellationInterruptsReadingTheBody() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}""").setBodyDelay(1, TimeUnit.SECONDS))
        val work = launch { client().health() }; withContext(Dispatchers.IO) { server.takeRequest() }
        withTimeout(500) { work.cancelAndJoin() }; assertTrue(work.isCancelled)
    }
    @Test fun protectedReadTimeoutStopsAfterOneSafeRetry() {
        health(); server.enqueue(MockResponse().setBody("{}").setBodyDelay(200, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(200, TimeUnit.MILLISECONDS))
        rejects(BackendProblem.TIMEOUT) { client(http.newBuilder().readTimeout(30, TimeUnit.MILLISECONDS).build()).checkConnection() }
        assertEquals(3, server.requestCount)
    }
    @Test fun startupTimeoutRecoversWithoutCredentialsOrUploading() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(504)); health()
        client().health()
        repeat(2) { val request = server.takeRequest(); assertEquals("/health", request.path); assertNull(request.getHeader("Authorization")) }
        assertEquals(2, server.requestCount)
    }
    @Test fun transientStatusFailureIsRetriedAsGetOnly() = runBlocking {
        health(); server.enqueue(MockResponse().setResponseCode(503)); status()
        assertEquals("ADMIN", client().checkConnection())
        server.takeRequest()
        repeat(2) { val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("/v1/admin/status", request.path) }
        assertEquals(3, server.requestCount)
    }
}
