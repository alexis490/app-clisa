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

@RunWith(AndroidJUnit4::class)
class ExcelReviewPanelTest {
    @get:Rule val ui = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var api: ClisaBackendClient
    @Before fun start() {
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server=MockWebServer();server.useHttps(tls.sslSocketFactory(),false);server.start()
        http=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
        api=ClisaBackendClient(object:BackendSession {
            override fun currentUid()="ISOLATED_ADMIN"
            override suspend fun credential(forceRefresh:Boolean)=BackendCredential(currentUid(),"isolated-test-token")
        },server.url("/").toString(),http)
    }
    @After fun stop(){server.shutdown();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()}
    private fun job(status:String="REVIEW_READY"):JSONObject {
        val review=testReviewReport(100,24576,5).put("uniqueProducts",99).put("newProducts",10).put("validProducts",90)
            .put("quality",JSONObject().put("duplicateCodes",2).put("duplicateSeries",1).put("emptySeries",1).put("negativeQuantities",1).put("otherErrors",0).put("negativeOnly",true))
            .put("comparison",JSONObject().put("unchanged",40).put("increased",20).put("decreased",20).put("newProducts",10).put("absent",8).put("unknownStock",0))
            .put("validation",JSONObject().put("validRecords",24500).put("separatedRecords",70))
        val issues=JSONArray()
        listOf("DUPLICATE_SKU","DUPLICATE_SKU","DUPLICATE_SERIAL","INVALID_SERIAL","INVALID_QUANTITY").forEachIndexed { n,type->issues.put(JSONObject()
            .put("type",type).put("productCode",if(n<2)"PRD-001" else "PRD-002").put("productName",if(n<2)"Cable HDMI" else "Mouse")
            .put("sheet","Datos").put("row",n+2).put("serial",if(type=="INVALID_SERIAL")"" else "SER-01").put("negative",type=="INVALID_QUANTITY")
            .put("description","Incidencia de prueba").put("reason","Corregir el origen")) }
        return JSONObject().put("id","12345678-1234-1234-1234-123456789abc").put("status",status).put("total",100)
            .put("cursor",if(status=="REVIEW_READY")100 else 0).put("confirmed",false).put("reviewBeforeClassification",true)
            .put("filename","Inventario_CLISA.xlsx").put("bytes",179937).put("rowCount",24576).put("issueCount",5)
            .put("items",JSONArray()).put("allIssues",issues).put("nextOffset",JSONObject.NULL).put("review",review)
            .put("preview",JSONObject().put("productsCreated",10).put("productsOmitted",10).put("classificationPending",1)).put("summary",JSONObject())
    }
    private fun open(value:JSONObject){ui.setContent { AppclisaTheme { ExcelImportAction(initialJob=value,backend=api) } };ui.onNodeWithText("Importar Excel").performClick()}
    private fun capture(name:String){ui.waitForIdle();Thread.sleep(250);ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")).use{it.readBytes()}}
    @Test fun showsMockupSectionsAndProductGroupedIncidents(){
        open(job())
        ui.onNodeWithText("Resumen del análisis").assertExists();ui.onNodeWithText("24,576").assertExists()
        ui.onNodeWithText("1. Calidad de datos").assertExists();ui.onNodeWithText("2. Comparación con Firebase").assertExists()
        ui.onNodeWithTag("continue-to-classification").assertIsEnabled().assertIsDisplayed()
        ui.onNodeWithText("Sincronizar con Firebase",substring=true).assertDoesNotExist()
        capture("review-analysis-top.png")
        ui.onNodeWithText("3. Resultado de validación").performScrollTo();ui.onNodeWithText("24,500").assertExists()
        capture("review-analysis-bottom.png")
        ui.onNodeWithTag("review-product-issues").performScrollTo().performClick()
        ui.onNodeWithText("Incidencias por producto").assertExists();ui.onNodeWithText("Cable HDMI").performClick()
        ui.onNodeWithText("Incidencias del producto").assertExists();ui.onNodeWithText("Datos · Fila 2").assertExists();ui.onNodeWithText("Datos · Fila 3").assertExists()
        capture("review-analysis-product-issues.png");assertEquals(0,server.requestCount)
    }
    @Test fun existingAnalysisPreparesItsReportWithoutStartingClassification(){
        server.enqueue(MockResponse().setBody(job().toString()))
        open(job().put("review",JSONObject.NULL))
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("continue-to-classification").assertIsEnabled()}.isSuccess}
        assertEquals(1,server.requestCount);assertTrue(server.takeRequest().path!!.endsWith("/review"))
        ui.onNodeWithText("Sincronizar con Firebase",substring=true).assertDoesNotExist()
    }
    @Test fun continueOnlyClassifiesAndFinalSummaryRequiresAnotherClick(){
        server.enqueue(MockResponse().setBody(job("CLASSIFICATION").toString()))
        server.enqueue(MockResponse().setBody(job("PREVIEW").toString()))
        server.enqueue(MockResponse().setBody(job("PREVIEW").toString()))
        open(job());ui.onNodeWithTag("continue-to-classification").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("Clasificación con información pendiente").fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("classification-to-summary").assertIsEnabled()}.isSuccess}
        ui.onNodeWithTag("classification-to-summary").performClick();ui.onNodeWithText("Sincronizar productos").assertExists()
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("sync-update-stock").assertIsEnabled()}.isSuccess}
        assertEquals(3,server.requestCount);assertTrue(server.takeRequest().path!!.endsWith("/classify"));assertTrue(server.takeRequest().path!!.endsWith("/advance"));assertEquals("GET",server.takeRequest().method)
    }
    @Test fun backArrowTraversesEveryStepAndForwardReusesSavedWork(){
        server.enqueue(MockResponse().setBody(job("PREVIEW").toString()))
        server.enqueue(MockResponse().setBody(job("PREVIEW").toString()))
        open(job("PREVIEW"))
        ui.onNodeWithTag("classification-to-summary").performClick()
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("sync-update-stock").assertIsEnabled()}.isSuccess}
        for(title in listOf("Clasificación de productos","Revisar análisis","Analizando Excel","Importar inventario")) {
            ui.onNodeWithTag("import-back").performClick();ui.onNodeWithText(title).assertExists()
        }
        ui.onNodeWithText("Inventario_CLISA.xlsx",substring=true).assertExists()
        assertEquals(1,server.requestCount)
        ui.onNodeWithTag("analyze-excel").assertIsEnabled().performClick()
        ui.onNodeWithText("Analizando Excel").assertExists()
        ui.onNodeWithTag("return-to-review").performClick();ui.onNodeWithText("Revisar análisis").assertExists()
        ui.onNodeWithTag("continue-to-classification").performClick();ui.onNodeWithText("Clasificación de productos").assertExists()
        assertEquals(1,server.requestCount)
        ui.onNodeWithTag("classification-to-summary").performClick()
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("sync-update-stock").assertIsEnabled()}.isSuccess}
        assertEquals(2,server.requestCount);repeat(2){assertEquals("GET",server.takeRequest().method)}
    }
    @Test fun browsingEarlierStepsDoesNotCancelClassificationOrJumpAhead(){
        server.enqueue(MockResponse().setBody(job("CLASSIFICATION").toString()))
        server.enqueue(MockResponse().setBody(job("PREVIEW").toString()).setBodyDelay(2,java.util.concurrent.TimeUnit.SECONDS))
        open(job("CLASSIFICATION"))
        repeat(3){ui.onNodeWithTag("import-back").performClick()}
        ui.onNodeWithText("Importar inventario").assertExists()
        ui.waitUntil(10000){server.requestCount==2 && runCatching{ui.onNodeWithTag("automatic-import-primary").assertIsEnabled()}.isSuccess}
        ui.onNodeWithText("Importar inventario").assertExists()
        ui.onNodeWithTag("analyze-excel").performClick();ui.onNodeWithTag("return-to-review").performClick()
        ui.onNodeWithTag("continue-to-classification").performClick()
        ui.onNodeWithText("Clasificación con información pendiente").assertExists()
        assertEquals(2,server.requestCount);assertEquals("GET",server.takeRequest().method);assertTrue(server.takeRequest().path!!.endsWith("/advance"))
    }

}
