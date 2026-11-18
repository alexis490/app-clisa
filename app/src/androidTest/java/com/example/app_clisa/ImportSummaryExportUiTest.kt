package com.example.app_clisa

import android.content.Intent
import android.net.Uri
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ImportSummaryExportUiTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var server:MockWebServer
    private lateinit var http:OkHttpClient
    private lateinit var api:ClisaBackendClient
    private lateinit var pages:JSONArray
    private lateinit var destination:File
    private var pendingRequest=-1
    private val titles=mutableListOf<String>()
    private lateinit var registry:ActivityResultRegistry
    @Before fun start(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        pages=JSONArray(InstrumentationRegistry.getInstrumentation().context.assets.open("export-fixture.json").bufferedReader().use{it.readText()})
        destination=File(context.getExternalFilesDir(null),"CLISA_export_android.xlsx");destination.delete()
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server=MockWebServer();server.useHttps(tls.sslSocketFactory(),false);server.start()
        http=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
        api=ClisaBackendClient(object:BackendSession{
            override fun currentUid()=pages.getJSONObject(0).getJSONObject("job").getJSONObject("history").getString("usuarioUid")
            override suspend fun credential(forceRefresh:Boolean)=BackendCredential(currentUid(),"isolated-test-token")
        },server.url("/").toString(),http)
        registry=object:ActivityResultRegistry(){
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
                val intent=contract.createIntent(context,input)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action)
                assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",intent.type)
                titles+=intent.getStringExtra(Intent.EXTRA_TITLE)!!;pendingRequest=requestCode
            }
        }
    }
    @After fun stop(){server.shutdown();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()}
    private fun enqueue(){repeat(pages.length()){server.enqueue(MockResponse().setBody(pages.getJSONObject(it).toString()).setBodyDelay(if(it==0)1 else 0,TimeUnit.SECONDS))}}
    private fun open(){
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=registry}
        ui.setContent{AppclisaTheme{CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner){ExcelImportAction(initialJob=pages.getJSONObject(0).getJSONObject("job"),backend=api)}}}
        ui.onNodeWithText("Importar Excel").performClick()
    }
    private fun waitPicker(){ui.waitUntil(15000){pendingRequest>=0}}
    private fun choose(uri:Uri?){ui.runOnIdle{registry.dispatchResult(pendingRequest,uri)}}
    @Test fun exportsFourSheetsThroughDocumentSelectorAndBlocksConcurrentExports(){
        enqueue();open();ui.onNodeWithTag("completed-export").performClick()
        ui.onNodeWithTag("completed-export").assertIsNotEnabled().performClick();waitPicker()
        assertEquals(1,titles.size);assertTrue(titles[0].matches(Regex("CLISA_Importacion_[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9a-f-]+\\.xlsx")))
        assertFalse(destination.exists());assertEquals(2,server.requestCount)
        choose(Uri.fromFile(destination))
        ui.waitUntil(10000){ui.onAllNodesWithText("Resumen exportado correctamente").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("completed-export").assertIsEnabled()
        ui.onNodeWithTag("completed-info-4").assertTextEquals(pages.getJSONObject(0).getJSONObject("job").getString("id"))
        ZipFile(destination).use{zip->assertEquals(4,zip.entries().asSequence().count{it.name.matches(Regex("xl/worksheets/sheet[1-4]\\.xml"))})}
        destination.copyTo(File(destination.parentFile,"CLISA_export_verificado_android.xlsx"),overwrite=true)
        repeat(2){val request=server.takeRequest();assertEquals("GET",request.method);assertTrue(request.path!!.contains("/export?"));assertFalse(request.path!!.contains("/confirm"))}
    }
    @Test fun cancelingTheSelectorLeavesImportUnchanged(){
        enqueue();open();ui.onNodeWithTag("completed-export").performClick();waitPicker();choose(null)
        ui.onNodeWithTag("completed-export").assertIsEnabled();ui.onNodeWithText("Resumen exportado correctamente").assertDoesNotExist()
        assertFalse(destination.exists());ui.onNodeWithText("Importación completada").assertExists();assertEquals(2,server.requestCount)
    }
    @Test fun providerWriteFailureIsClearAndDoesNotAlterCompletedImport(){
        enqueue();open();ui.onNodeWithTag("completed-export").performClick();waitPicker();choose(Uri.parse("content://no-such-provider/blocked.xlsx"))
        ui.waitUntil(10000){ui.onAllNodesWithText("No se pudo guardar el resumen.",substring=true).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("completed-export").assertIsEnabled();ui.onNodeWithText("Importación completada").assertExists()
        ui.onNodeWithTag("completed-info-4").assertTextEquals(pages.getJSONObject(0).getJSONObject("job").getString("id"))
    }
    @Test fun backendFailureDoesNotOpenSelectorOrChangeImport(){
        server.enqueue(MockResponse().setResponseCode(403));open();ui.onNodeWithTag("completed-export").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("No se pudo exportar el resumen:",substring=true).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("completed-export").assertIsEnabled();assertTrue(titles.isEmpty());assertEquals(1,server.requestCount)
    }
}
