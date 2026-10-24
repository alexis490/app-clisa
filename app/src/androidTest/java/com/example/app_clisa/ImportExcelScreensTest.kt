package com.example.app_clisa

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.data.excel.*
import com.example.app_clisa.ui.products.*
import com.example.app_clisa.ui.theme.AppclisaTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated Compose host. No MainActivity, Firebase identity or production inventory queries. */
@RunWith(AndroidJUnit4::class)
class ImportExcelScreensTest {
    @get:Rule val ui = createComposeRule()
    private val report = ExcelInventory(listOf(ExcelProduct("00780", "Producto local", 1,
        listOf(ExcelSerial("0007", 1, "Excel", 3)), "Excel", 2, 1)), emptyList(), 0, 1)

    private fun capture(name: String) {
        ui.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$name")).use { it.readBytes() }
    }
    @Test fun importButtonOpensSelectionPanelBeforeFilePicker() {
        ui.setContent { AppclisaTheme { ExcelImportAction() } }
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithText("Importar inventario").assertExists()
        ui.onNodeWithTag("import-pick").assertIsEnabled()
        ui.onNodeWithTag("import-analyze").assertIsNotEnabled()
        ui.onAllNodesWithText("Sin verificar").assertCountEquals(4)
    }
    @Test fun emptySelectionCannotAnalyzeAndCallbacksRemainAvailable() {
        var picked = 0
        var closed = 0
        ui.setContent { AppclisaTheme { ImportSelectionScreen("", null, null, null, false,
            { closed++ }, { picked++ }, {}, { fail("An empty file must not be analyzed") }) } }
        ui.onNodeWithTag("import-analyze").assertIsNotEnabled()
        ui.onNodeWithTag("import-pick").performClick()
        ui.runOnIdle { assertEquals(1, picked) }
        capture("clisa-import-select-empty.png")
        ui.onNodeWithTag("import-back").performClick()
        ui.runOnIdle { assertEquals(1, closed) }
    }
    @Test fun selectedFileUsesCachedComparisonAndClearReturnsToEmptyState() {
        var selected by mutableStateOf(true)
        var reviewed = 0
        ui.setContent { AppclisaTheme { ImportSelectionScreen(if (selected) "Prueba local.xlsx" else "", if (selected) 2048 else null,
            if (selected) report else null, null, selected, {}, {}, { selected = false }, { reviewed++ }) } }
        ui.onNodeWithText("Revisar diferencias").assertExists()
        ui.onNodeWithTag("import-analyze").performClick()
        ui.runOnIdle { assertEquals(1, reviewed) }
        ui.onNodeWithTag("import-clear").performScrollTo().performClick()
        ui.onNodeWithTag("import-analyze").assertIsNotEnabled()
    }
    @Test fun progressShowsObservedCountsWithoutInventedPercentage() {
        var cancelled = 0
        ui.setContent { AppclisaTheme { ImportAnalysisScreen("Prueba local · sin conexión.xlsx", 2048, report, false,
            ComparisonProgress("productos", 500, 0)) { cancelled++ } } }
        ui.onNodeWithText("500 productos leídos").assertExists()
        ui.onNodeWithText("68%").assertDoesNotExist()
        capture("clisa-import-analysis-local.png")
        ui.onNodeWithTag("import-cancel").performClick()
        ui.runOnIdle { assertEquals(1, cancelled) }
    }
    @Test fun actualExcelCanPopulateSelectionAndPreviewWithoutFirestore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = InstrumentationRegistry.getArguments().getString("excelSample")
        assumeTrue("Provide the local sample through the instrumentation argument", source != null)
        require(source == "/sdcard/Download/clisa-import-validation.xlsx")
        val temporary = File.createTempFile("clisa-ui-real-", ".xlsx", instrumentation.targetContext.cacheDir)
        val real = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $source")).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            XlsxInventoryReader().read(temporary)
        } finally { temporary.delete() }
        assertEquals(1814, real.products.size)
        assertEquals(22762, real.serialRowCount)
        assertEquals(567, real.errorCount)
        val comparison = InventoryComparator.compare(real, InventoryReadSnapshot(emptyList(), emptyList(), 1, 2))
        val preview = InventoryPreviewBuilder.build(comparison, real)
        assertEquals(comparison.newExistences.size, preview.groups.getValue(PreviewGroup.NEW_EXISTENCES).size)
        assertEquals("00780", preview.products.single { it.code == "00780" }.code)
        var review by mutableStateOf(false)
        ui.setContent { AppclisaTheme {
            if (review) InventoryPreviewScreen(preview, comparison, real, "Excel real · comparación local sin Firestore", {}, { review = false })
            else ImportSelectionScreen("stock.production.lot123 - 2000.xlsx", 1005411, real, null, false, {}, {}, {}, { review = true })
        } }
        ui.onNodeWithTag("import-analyze").assertIsEnabled()
        ui.onNodeWithTag("import-selection").performScrollToNode(hasTestTag("import-read-details"))
        ui.onNodeWithTag("import-read-details").performClick()
        ui.onNodeWithTag("import-selection").performScrollToNode(hasText("Códigos únicos: 1453 · Grupos sin código: 346"))
        ui.onNodeWithText("Códigos únicos: 1453 · Grupos sin código: 346").assertExists()
        ui.onNodeWithTag("import-read-details").performScrollTo().performClick()
        ui.onNodeWithTag("import-selection").performScrollToIndex(0)
        capture("clisa-import-select-real.png")
        ui.onNodeWithTag("import-analyze").performClick()
        ui.onNodeWithText("Revisar sincronización").assertExists()
        ui.onNodeWithTag("preview-step4").assertIsNotEnabled()
    }
}
