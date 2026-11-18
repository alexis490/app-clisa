package com.example.app_clisa

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.data.backend.*
import com.example.app_clisa.ui.products.ExcelImportAction
import com.example.app_clisa.ui.theme.AppclisaTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportHistoryPanelTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var server:MockWebServer
    private lateinit var http:OkHttpClient
    private lateinit var api:ClisaBackendClient
    private lateinit var fixture:JSONObject
    @Before fun start(){
        fixture=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("history-result.json").bufferedReader().use{it.readText()})
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server=MockWebServer();server.useHttps(tls.sslSocketFactory(),false);server.start()
        http=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
        api=ClisaBackendClient(object:BackendSession{
            override fun currentUid()=fixture.getJSONObject("history").getString("usuarioUid")
            override suspend fun credential(forceRefresh:Boolean)=BackendCredential(currentUid(),"isolated-test-token")
        },server.url("/").toString(),http)
    }
    @After fun stop(){server.shutdown();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()}
    private fun open(value:JSONObject){
        server.enqueue(MockResponse().setBody(value.toString()))
        val verified=runBlocking{api.automaticJob(value.getString("id"))}
        ui.setContent{AppclisaTheme{ExcelImportAction(initialJob=verified,backend=api)}}
        ui.onNodeWithText("Importar Excel").performClick()
    }
    @Test fun showsRecordActuallySavedByFirestoreEmulator(){
        open(fixture);val history=fixture.getJSONObject("history")
        ui.onNodeWithTag("completed-info-0").assertTextEquals(history.getString("archivo"))
        ui.onNodeWithTag("completed-info-1").assert(SemanticsMatcher("Fecha registrada formateada"){node->node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text){emptyList()}.joinToString(""){it.text}.matches(Regex("[0-9]{2}/[0-9]{2}/[0-9]{4} · [0-9]{2}:[0-9]{2}"))})
        ui.onNodeWithTag("completed-info-2").assertTextEquals(history.getString("usuario"))
        ui.onNodeWithTag("completed-info-3").assertTextEquals("Firebase Firestore")
        ui.onNodeWithTag("completed-info-4").assertTextEquals(history.getString("idImportacion"))
        ui.onNodeWithTag("completed-export").assertIsEnabled()
        ui.waitForIdle();Thread.sleep(250)
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/history-completed.png")).use{it.readBytes()}
        assertEquals(1,server.requestCount);assertEquals("GET",server.takeRequest().method)
    }
    @Test fun failedHistoryNeverClaimsInventoryWasUpdated(){
        val failed=JSONObject(fixture.toString()).put("status","PARTIAL")
        failed.getJSONObject("summary").put("syncErrors",1).put("productsCreated",0).put("productsUpdated",0).put("productsUnchanged",0).put("seriesCreated",0)
        failed.getJSONObject("history").put("estado","FALLIDO")
        open(failed);ui.onNodeWithText("Importación fallida").assertExists()
        ui.onNodeWithText("Importación finalizada").assertDoesNotExist()
        ui.onNodeWithTag("completed-info-2").assertTextEquals(failed.getJSONObject("history").getString("usuario"))
    }
    @Test fun completedWithObservationsKeepsItsActualState(){
        val value=JSONObject(fixture.toString()).put("total",2).put("cursor",2)
        value.getJSONObject("summary").put("productsOmitted",1)
        value.getJSONObject("history").put("estado","COMPLETADO_CON_OBSERVACIONES")
        open(value);ui.onNodeWithText("Importación finalizada con observaciones").assertExists()
        ui.onNodeWithTag("completed-info-4").assertTextEquals(value.getString("id"))
    }

}
