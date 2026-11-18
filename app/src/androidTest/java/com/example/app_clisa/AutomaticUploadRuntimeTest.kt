package com.example.app_clisa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Android JSON/TLS/multipart runtime, with an isolated HTTPS service and exact source bytes. */
@RunWith(AndroidJUnit4::class)
class AutomaticUploadRuntimeTest {
    @Test fun exactUserFilePassesAutomaticUploadOnAndroid() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("actual-excel-", ".xlsx", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("upload-source.xlsx").use { input -> file.outputStream().use { input.copyTo(it) } }
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(tls.sslSocketFactory(), false); server.start()
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        try {
            val session = object : BackendSession {
                override fun currentUid() = "ISOLATED_TEST_ADMIN"
                override suspend fun credential(forceRefresh: Boolean) = BackendCredential(currentUid(), "isolated-local-test-token")
            }
            val api = ClisaBackendClient(session, server.url("/").toString(), http)
            fun enqueue(value: JSONObject) = server.enqueue(MockResponse().setBody(value.toString()))
            val status = JSONObject().put("mode", "AUTOMATIC_IMPORT").put("inventoryWritesEnabled", true)
                .put("capabilities", JSONArray().put("automatic-import"))
            server.enqueue(MockResponse().setResponseCode(503))
            enqueue(JSONObject().put("status", "ok")); enqueue(status)
            assertEquals(session.currentUid(), api.checkConnection())
            enqueue(JSONObject().put("status", "ok")); enqueue(status)
            enqueue(JSONObject().put("id", "12345678-1234-1234-1234-123456789abc").put("status", "COMPARISON")
                .put("total", 366).put("cursor", 0).put("items", JSONArray()).put("preview", JSONObject()).put("summary", JSONObject())
                .put("fileSha256", file.inputStream().use(SyncPlanIdentity::fileSha256)))
            assertEquals(179937L, file.length())
            assertEquals("COMPARISON", api.uploadAutomatic(file, "stock.production.lot123.xlsx").getString("status"))
            repeat(5) { server.takeRequest() }
            val upload = server.takeRequest()
            assertEquals("POST", upload.method)
            assertEquals("/v1/admin/excel-imports", upload.path)
            assertTrue(upload.body.readUtf8().contains("stock.production.lot123.xlsx"))
            assertEquals(6, server.requestCount) // One safe health retry, no advance/confirm or production access.
        } finally {
            server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown(); file.delete()
        }
    }
}
