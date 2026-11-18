package com.example.app_clisa

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
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
class ExcelSyncPanelTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var server:MockWebServer
    private lateinit var http:OkHttpClient
    private lateinit var api:ClisaBackendClient
    @Before fun start(){
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("excel-import-resume",0).edit().remove("ISOLATED_ADMIN").commit()
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server=MockWebServer();server.useHttps(tls.sslSocketFactory(),false);server.start()
        http=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
        api=ClisaBackendClient(object:BackendSession {
            override fun currentUid()="ISOLATED_ADMIN"
            override suspend fun credential(forceRefresh:Boolean)=BackendCredential(currentUid(),"isolated-test-token")
        },server.url("/").toString(),http)
    }
    @After fun stop(){
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("excel-import-resume",0).edit().remove("ISOLATED_ADMIN").commit()
        server.shutdown();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()
    }
    private fun job(status:String="PREVIEW",cursor:Int=0)=JSONObject().put("id","12345678-1234-1234-1234-123456789abc")
        .put("status",status).put("confirmed",status!="PREVIEW").put("total",100).put("cursor",cursor)
        .put("reviewBeforeClassification",true).put("filename","stock.production.lot123.xlsx").put("bytes",179937).put("rowCount",4043)
        .put("items",JSONArray()).put("allIssues",JSONArray()).put("nextOffset",JSONObject.NULL)
        .put("preview",JSONObject().put("productsCreated",15).put("productsUpdated",70).put("productsUnchanged",13).put("productsOmitted",2).put("classificationPending",3))
        .put("summary",JSONObject().put("productsCreated",15).put("productsUpdated",70).put("productsUnchanged",13).put("productsOmitted",2).put("classificationPending",3).put("syncErrors",0))
    private fun open(){ui.setContent{AppclisaTheme{ExcelImportAction(initialJob=job(),backend=api)}};ui.onNodeWithText("Importar Excel").performClick();ui.onNodeWithTag("classification-to-summary").performClick()}
    private fun ready(){ui.waitUntil(10000){runCatching{ui.onNodeWithTag("sync-update-stock").assertIsEnabled()}.isSuccess}}
    private fun capture(name:String){ui.waitForIdle();Thread.sleep(250);ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")).use{it.readBytes()}}
    @Test fun connectionIsRealAndBothCancelActionsNeverConfirm(){
        server.enqueue(MockResponse().setBody(job().toString()).setBodyDelay(1,TimeUnit.SECONDS))
        open();ui.onNodeWithTag("sync-update-stock").assertIsNotEnabled();ready()
        ui.onNodeWithText("Servicios conectados").assertExists();ui.onAllNodesWithText("✓ Conectado").assertCountEquals(2)
        ui.onNodeWithText("98").assertExists();ui.onNodeWithText("0%").assertExists();capture("sync-ready.png")
        ui.onNodeWithTag("sync-update-stock").performClick();ui.onNodeWithTag("sync-confirmation").assertExists()
        ui.onNodeWithText("¿Estás seguro de actualizar el stock?").assertExists();capture("sync-confirmation.png")
        ui.onNodeWithTag("sync-confirm-cancel").performClick();ui.onNodeWithTag("sync-confirmation").assertDoesNotExist()
        ui.onNodeWithTag("sync-update-stock").performClick();ui.onNodeWithTag("sync-confirm-close").performClick()
        ui.onNodeWithTag("sync-cancel").performClick();ui.onNodeWithText("Sincronizar productos").assertDoesNotExist()
        assertEquals(1,server.requestCount);assertEquals("GET",server.takeRequest().method)
    }
    @Test fun onlyConfirmationStartsSyncAndProgressCannotStartAnotherUpdate(){
        server.enqueue(MockResponse().setBody(job().toString()))
        server.enqueue(MockResponse().setBody(job("SYNCING").toString()))
        server.enqueue(MockResponse().setBody(job("SYNCING",68).toString()))
        server.enqueue(MockResponse().setBody(job("COMPLETED",100).toString()).setBodyDelay(3,TimeUnit.SECONDS))
        open();ready();ui.onNodeWithTag("sync-update-stock").performClick();assertEquals(1,server.requestCount)
        ui.onNodeWithTag("sync-confirm-update").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("68%").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("sync-update-stock").assertIsNotEnabled();ui.onNodeWithText("Cerrar seguimiento").assertExists();capture("sync-progress.png")
        ui.waitUntil(15000){ui.onAllNodesWithText("Importación finalizada").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(4,server.requestCount)
        assertEquals("GET",server.takeRequest().method)
        val confirm=server.takeRequest();assertEquals("POST",confirm.method);assertTrue(confirm.path!!.endsWith("/confirm"))
        repeat(2){assertTrue(server.takeRequest().path!!.endsWith("/advance"))}
    }
    @Test fun failedConnectionBlocksWritesAndRetryReadsOnly(){
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"));server.enqueue(MockResponse().setBody(job().toString()))
        open();ui.waitUntil(10000){ui.onAllNodesWithText("Reintentar conexión").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("sync-update-stock").assertIsNotEnabled();ui.onAllNodesWithText("✓ Conectado").assertCountEquals(0)
        ui.onNodeWithText("Reintentar conexión").performClick();ready()
        assertEquals(2,server.requestCount);repeat(2){assertEquals("GET",server.takeRequest().method)}
    }
    @Test fun legacyPreviewUsesSyncPanelAndKeepsConfirmation(){
        val legacy=job().put("reviewBeforeClassification",false)
        server.enqueue(MockResponse().setBody(legacy.toString()))
        ui.setContent{AppclisaTheme{ExcelImportAction(initialJob=legacy,backend=api)}}
        ui.onNodeWithText("Importar Excel").performClick();ready()
        ui.onNodeWithText("Sincronizar productos").assertExists()
        ui.onNodeWithText("Análisis completado").assertDoesNotExist()
        ui.onNodeWithText("Vista previa de cambios").assertDoesNotExist()
        ui.onNodeWithTag("sync-update-stock").performClick()
        ui.onNodeWithTag("sync-confirmation").assertExists()
        ui.onNodeWithTag("sync-confirm-cancel").performClick()
        ui.onNodeWithTag("sync-confirmation").assertDoesNotExist()
        assertEquals(1,server.requestCount)
        assertEquals("GET",server.takeRequest().method)
    }
    @Test fun completedBackClosesFlowAndNextImportStartsAtSelection(){
        ui.setContent{AppclisaTheme{ExcelImportAction(initialJob=job("COMPLETED"),backend=api)}}
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithText("Importación completada").assertExists()
        ui.onNodeWithTag("import-back").performClick()
        ui.onNodeWithText("Importación completada").assertDoesNotExist()
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithText("Selecciona un archivo Excel").assertExists()
        ui.onNodeWithText("Analizando Excel").assertDoesNotExist()
        assertEquals(0,server.requestCount)
    }
    @Test fun savedImportDoesNotBlockFilePickerOrStartNetworkRequests(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("excel-import-resume",0).edit().putString("ISOLATED_ADMIN",job().getString("id")).commit()
        var pickerRequest:Int?=null
        val registry=object:ActivityResultRegistry(){
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,contract.createIntent(context,input).action)
                pickerRequest=requestCode
            }
        }
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=registry}
        ui.setContent{AppclisaTheme{CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner){ExcelImportAction(backend=api)}}}
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithTag("resume-saved-import").assertExists()
        ui.onNodeWithTag("automatic-import-primary").assertIsEnabled().performClick()
        ui.runOnIdle{assertNotNull(pickerRequest);registry.dispatchResult(pickerRequest!!,null as Uri?)}
        ui.onNodeWithTag("automatic-import-primary").assertIsEnabled()
        ui.onNodeWithTag("analyze-excel").assertIsNotEnabled()
        assertEquals(0,server.requestCount)
    }
    @Test fun savedImportIsFetchedOnlyAfterExplicitResume(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("excel-import-resume",0).edit().putString("ISOLATED_ADMIN",job().getString("id")).commit()
        server.enqueue(MockResponse().setBody(job("COMPLETED",100).toString()))
        ui.setContent{AppclisaTheme{ExcelImportAction(backend=api)}}
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithTag("automatic-import-primary").assertIsEnabled()
        assertEquals(0,server.requestCount)
        ui.onNodeWithTag("resume-saved-import").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("Importación completada").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(1,server.requestCount)
        assertEquals("GET",server.takeRequest().method)
    }
}
