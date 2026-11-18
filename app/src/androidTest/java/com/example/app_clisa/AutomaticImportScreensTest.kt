package com.example.app_clisa

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.ui.products.*
import com.example.app_clisa.ui.theme.AppclisaTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor

@RunWith(AndroidJUnit4::class)
class AutomaticImportScreensTest {
    @get:Rule val ui = createComposeRule()
    private fun capture(name: String) {
        ui.waitForIdle()
        Thread.sleep(300) // Let the dialog surface reach the screenshot compositor.
        val instrument = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")).use { it.readBytes() }
    }
    private fun job(status: String) = JSONObject().put("id", "12345678-1234-1234-1234-123456789abc").put("status", status)
        .put("filename", "stock.production.lot123 - 2000.xlsx").put("bytes", 1005411).put("total", 1814).put("rowCount", 24576).put("issueCount", 42).put("cursor", 0)
        .put("items", JSONArray()).put("nextOffset", JSONObject.NULL)
        .put("preview", JSONObject().put("productsCreated", 15).put("productsUpdated", 1742).put("seriesCreated", 20).put("seriesUpdated", 320).put("seriesZeroed", 480).put("productsOmitted", 42).put("classificationPending", 32))
        .put("summary", JSONObject().put("productsCreated", 15).put("productsUpdated", 1742).put("seriesUpdated", 320).put("seriesZeroed", 480).put("productsOmitted", 42).put("syncErrors", if(status == "PARTIAL") 1 else 0))
    @Test fun automaticEntryShowsCompatibleFormats() {
        ui.setContent { AppclisaTheme { ExcelImportAction() } }
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithText("Importar inventario").assertExists()
        ui.onNodeWithTag("automatic-import-primary").assertIsEnabled()
        ui.onNodeWithText("Referencia interna").assertExists()
        ui.onNodeWithText("Lote / N° de serie").assertExists()
        ui.onNodeWithTag("analyze-excel").assertIsNotEnabled()
        for (key in listOf("serial", "code", "name", "quantity")) ui.onNodeWithTag("format-check-$key").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Pendiente"))
        ui.onNodeWithText("Seleccionar → analizar → revisar → sincronizar → resultados").assertDoesNotExist()
        capture("automatic-selection.png")
    }
    @Test fun partialResultsDisplayErrorsAndNeverClaimTotalSuccess() {
        ui.setContent { AppclisaTheme { ExcelImportAction(initialJob = job("PARTIAL")) } }
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithText("Finalizada con errores de sincronización").assertExists()
        ui.onNodeWithText("Importación finalizada").assertDoesNotExist()
        capture("automatic-results-partial.png")
    }
    @Test fun processingShowsExactlyFourStages() {
        ui.setContent { AppclisaTheme { InventoryImportFrame("Procesando inventario", {}) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(12.dp)) { InventoryProcessing("CLASSIFICATION", 50, 1814) }
        } } }
        for (label in listOf("Lectura del archivo", "Validación de datos", "Comparación con Firebase", "Clasificación de productos")) ui.onNodeWithText(label).assertExists()
        ui.onNodeWithText("50 de 1814 productos").assertExists()
        capture("automatic-processing.png")
    }
    @Test fun incompleteFormatLeavesMissingMarkGrayAndAnalyzeDisabled() {
        ui.setContent { AppclisaTheme { InventoryImportFrame("Importar inventario", {}) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f).padding(12.dp)) {
                InventorySelection("stock.production.lot123.xlsx", 179937, setOf("serial", "code", "name")) {}
            }
            InventoryAnalyzeButton(false, false, true) { error("No debe comenzar análisis") }
        } } }
        ui.onNodeWithTag("format-check-code").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Detectado"))
        ui.onNodeWithTag("format-check-quantity").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Pendiente"))
        ui.onNodeWithTag("analyze-excel").assertIsNotEnabled()
        capture("format-gate-incomplete.png")
    }
    @Test fun completedResultsUseMockupActions() {
        ui.setContent { AppclisaTheme { ExcelImportAction(initialJob = job("COMPLETED").put("summary", JSONObject().put("productsCreated",1).put("productsUpdated",32).put("productsUnchanged",0).put("seriesUpdated",2).put("seriesZeroed",1).put("productsOmitted",11).put("syncErrors",0))) } }
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithText("Importación finalizada").assertExists()
        ui.onNodeWithText("Acciones").assertExists()
        ui.onNodeWithText("Resumen de actualización").assertExists()
        ui.onNodeWithText("Información de la importación").assertExists()
        ui.onNodeWithTag("completed-export").assertIsEnabled()
        repeat(5){ui.onNodeWithTag("completed-info-$it").assertTextEquals("—")}
        capture("automatic-results.png")
        ui.onNodeWithText("⌂  Volver al inicio").performClick()
        ui.onNodeWithText("Importación completada").assertDoesNotExist()
    }
    @Test fun incidentsOpenLocationAndReturnToList() {
        val issue = JSONObject().put("type", "DUPLICATE_SERIAL").put("description", "Se encontró más de una fila con el mismo número de serie.")
            .put("sheet", "Hoja 1").put("row", 42).put("productCode", "PRD-00125").put("productName", "CABLE HDMI 2M").put("serial", "SN-00125")
        val data = job("PREVIEW").put("allIssues", JSONArray().put(issue))
        ui.setContent { AppclisaTheme { InventoryImportFrame("Incidencias detectadas", {}) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(12.dp)) { InventoryDetails(data, emptyList(), true) }
        } } }
        ui.onNodeWithText("Serie duplicada").assertExists()
        capture("automatic-incidents.png")
        ui.onNodeWithText("Serie duplicada").performClick()
        ui.onNodeWithText("Ubicación en el archivo").assertExists()
        ui.onAllNodesWithText("Serie: SN-00125").onLast().assertExists()
        capture("automatic-incident-detail.png")
        ui.onNodeWithText("Ver en lista").performClick()
        ui.onNodeWithText("Ubicación en el archivo").assertDoesNotExist()
    }
    @Test fun classificationShowsRealProposedFields() {
        val row = JSONObject().put("name", "Mouse Logitech M220").put("code", "LOGI-M220").put("plan", JSONObject().put("classification", JSONObject()
            .put("pending", JSONArray().put("marcaId")).put("categoryName", "Periféricos").put("brandName", "Logitech").put("source", "Gemini")
            .put("fields", JSONObject().put("descripcion", "Mouse inalámbrico Logitech M220"))))
        ui.setContent { AppclisaTheme { InventoryImportFrame("Clasificación de productos", {}) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(12.dp)) { InventoryDetails(job("PREVIEW"), listOf(row), false) }
        } } }
        capture("automatic-classification.png")
        ui.onNodeWithText("Mouse Logitech M220").performClick()
        ui.onNodeWithText("Periféricos").assertExists()
        ui.onNodeWithText("Fuente de clasificación").assertExists()
        capture("automatic-classification-detail.png")
    }
}
