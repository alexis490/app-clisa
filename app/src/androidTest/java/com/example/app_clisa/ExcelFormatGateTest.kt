package com.example.app_clisa

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.data.backend.*
import com.example.app_clisa.data.excel.SyncPlanIdentity
import com.example.app_clisa.ui.products.ExcelImportAction
import com.example.app_clisa.ui.theme.AppclisaTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.os.ParcelFileDescriptor

@RunWith(AndroidJUnit4::class)
class ExcelFormatGateTest {
    @get:Rule val ui = createComposeRule()
    @Test fun selectingTheRealExcelOnlyChecksFormatAndWaitsForAnalyzeClick() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File.createTempFile("stock-", ".xlsx", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("upload-source.xlsx").use { input -> source.outputStream().use { input.copyTo(it) } }
        val hash = source.inputStream().use(SyncPlanIdentity::fileSha256)
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer(); server.useHttps(tls.sslSocketFactory(), false); server.start()
        val responses = java.util.concurrent.LinkedBlockingQueue<MockResponse>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val response = responses.take()
                if (request.path == "/v1/admin/excel-imports") {
                    val operationId = Regex("\"importId\":\"([^\"]+)\"").find(request.body.clone().readUtf8())!!.groupValues[1]
                    val value = JSONObject(response.getBody()!!.clone().readUtf8()).put("id", operationId)
                    response.setBody(value.toString())
                }
                return response
            }
        }
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val api = ClisaBackendClient(object : BackendSession {
            override fun currentUid() = "LOCAL_TEST_ADMIN"
            override suspend fun credential(forceRefresh: Boolean) = BackendCredential(currentUid(), "isolated-test-token")
        }, server.url("/").toString(), http)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Uri.fromFile(source))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        fun enqueue(value: JSONObject) = responses.put(MockResponse().setBody(value.toString()))
        fun capture(name: String) { ui.waitForIdle(); Thread.sleep(250); ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")).use { it.readBytes() } }
        try {
            enqueue(JSONObject().put("status", "ok"))
            enqueue(JSONObject().put("status", "FORMAT_CHECKED").put("fileSha256", hash).put("ready", true)
                .put("checks", JSONObject().put("serial", true).put("code", true).put("name", true).put("quantity", true)))
            ui.setContent { AppclisaTheme { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { ExcelImportAction(backend = api) } } }
            ui.onNodeWithText("Importar Excel").performClick()
            ui.onNodeWithTag("analyze-excel").assertIsNotEnabled()
            for (key in listOf("serial", "code", "name", "quantity")) ui.onNodeWithTag("format-check-$key").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Pendiente"))
            capture("format-gate-initial.png")
            ui.onNodeWithTag("automatic-import-primary").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("✓ Válido").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("analyze-excel").assertIsEnabled()
            ui.onNodeWithText("Importar inventario").assertExists()
            ui.onNodeWithText("Analizando Excel").assertDoesNotExist()
            for (key in listOf("serial", "code", "name", "quantity")) ui.onNodeWithTag("format-check-$key").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Detectado"))
            assertEquals(2, server.requestCount)
            assertEquals("/health", server.takeRequest().path)
            assertEquals("/v1/admin/excel-format", server.takeRequest().path)
            capture("format-gate-ready.png")
            enqueue(JSONObject().put("status", "ok"))
            enqueue(JSONObject().put("status", "FORMAT_CHECKED").put("fileSha256", hash).put("ready", false)
                .put("checks", JSONObject().put("serial", true).put("code", true).put("name", true).put("quantity", false)))
            ui.onNodeWithTag("automatic-import-primary").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("Faltan campos del formato esperado.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("analyze-excel").assertIsNotEnabled()
            ui.onNodeWithTag("format-check-quantity").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Pendiente"))
            assertEquals(4, server.requestCount)
            enqueue(JSONObject().put("status", "ok"))
            enqueue(JSONObject().put("status", "FORMAT_CHECKED").put("fileSha256", hash).put("ready", true)
                .put("checks", JSONObject().put("serial", true).put("code", true).put("name", true).put("quantity", true)))
            ui.onNodeWithTag("automatic-import-primary").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("✓ Válido").fetchSemanticsNodes().isNotEmpty() }
            repeat(4) { assertFalse(server.takeRequest().path!!.contains("excel-imports")) }
            enqueue(JSONObject().put("status", "ok"))
            enqueue(JSONObject().put("mode", "AUTOMATIC_IMPORT").put("inventoryWritesEnabled", true))
            enqueue(JSONObject().put("id", "12345678-1234-1234-1234-123456789abc").put("status", "REVIEW_READY").put("total", 366).put("cursor", 366).put("confirmed", false)
                .put("items", JSONArray()).put("rowCount",4043).put("review",testReviewReport(366,4043)).put("preview", JSONObject()).put("summary", JSONObject()).put("fileSha256", hash))
            ui.onNodeWithTag("analyze-excel").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("Revisar análisis").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(9, server.requestCount)
            server.takeRequest(); server.takeRequest(); assertEquals("/v1/admin/excel-imports", server.takeRequest().path)
        } finally { server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown(); source.delete() }
    }
}
