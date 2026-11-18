package com.example.app_clisa

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.data.backend.*
import com.example.app_clisa.ui.products.ExcelImportAction
import com.example.app_clisa.ui.theme.AppclisaTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExcelAnalysisPanelTest {
    @get:Rule val ui = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var api: ClisaBackendClient
    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer(); server.useHttps(tls.sslSocketFactory(), false); server.start()
        http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        api = ClisaBackendClient(object : BackendSession {
            override fun currentUid() = "ISOLATED_ADMIN"
            override suspend fun credential(forceRefresh: Boolean) = BackendCredential(currentUid(), "isolated-test-token")
        }, server.url("/").toString(), http)
    }
    @After fun stop() { server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    private fun job(cursor: Int = 0, review: Boolean = false) = JSONObject().put("id", "12345678-1234-1234-1234-123456789abc")
        .put("status", if (review) "REVIEW_READY" else "COMPARISON").put("cursor", cursor).put("total", 5).put("confirmed", false)
        .put("filename", "stock.production.lot123.xlsx").put("bytes", 179937).put("rowCount", 18).put("issueCount", 2)
        .put("items", JSONArray()).put("preview", JSONObject()).put("summary", JSONObject()).put("nextOffset", JSONObject.NULL)
        .put("analysis", JSONObject().put("rowsRead", 18).put("validProducts", 4).put("inventoryTarget", 4)
            .put("inventoryChecked", if (review) 4 else cursor.coerceAtMost(4)).put("productsCompared", cursor)
            .put("seriesTotal", 12).put("seriesProcessed", if (review) 12 else cursor * 2).put("conflictsChecked", cursor).put("excludedProducts", if (review) 1 else 0))
    private fun capture(name: String) {
        ui.waitForIdle(); Thread.sleep(250)
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")).use { it.readBytes() }
    }
    @Test fun showsRealCountersAndAutomaticallyStopsAtReview() {
        server.enqueue(MockResponse().setBody(job().toString()))
        server.enqueue(MockResponse().setBody(job(2).toString()))
        server.enqueue(MockResponse().setBody(job(5, true).toString()).setBodyDelay(3, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody(job(5, true).put("review", testReviewReport(5,18,2)).toString()))
        ui.setContent { AppclisaTheme { ExcelImportAction(initialJob = job(), backend = api) } }
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithText("Analizando Excel").assertExists()
        ui.waitUntil(10000) { ui.onAllNodesWithText("2 de 5 productos").fetchSemanticsNodes().isNotEmpty() }
        for (tag in listOf("reading", "validation", "inventory", "comparison", "serials", "conflicts")) ui.onNodeWithTag("analysis-$tag").assertExists()
        ui.onNodeWithTag("analysis-conflicts").assertIsDisplayed()
        ui.onNodeWithText("4 de 12 series revisadas o apartadas").assertExists()
        ui.onNodeWithTag("cancel-excel-analysis").assertExists()
        ui.onNodeWithText("Sincronizar con Firebase", substring = true).assertDoesNotExist()
        capture("excel-analysis-progress.png")
        ui.waitUntil(10000) { ui.onAllNodesWithText("Revisar análisis").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Analizando Excel").assertDoesNotExist()
        ui.onNodeWithText("Sincronizar con Firebase", substring = true).assertDoesNotExist()
        ui.waitUntil(10000) { runCatching { ui.onNodeWithTag("continue-to-classification").assertIsEnabled() }.isSuccess }
        ui.onNodeWithText("Resumen del análisis").assertExists()
        assertEquals(4, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        repeat(2) { val request = server.takeRequest(); assertTrue(request.path!!.endsWith("/advance")); assertEquals("POST", request.method) }
        assertTrue(server.takeRequest().path!!.endsWith("/review"))
        capture("excel-analysis-review-ready.png")
    }
    @Test fun cancelStopsRequestsAndClosesAnalysisWithoutConfirming() {
        server.enqueue(MockResponse().setBody(job().toString()).setBodyDelay(3, TimeUnit.SECONDS))
        ui.setContent { AppclisaTheme { ExcelImportAction(initialJob = job(), backend = api) } }
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithTag("cancel-excel-analysis").performClick()
        ui.onNodeWithText("Analizando Excel").assertDoesNotExist()
        assertTrue(server.requestCount <= 1)
    }
}
