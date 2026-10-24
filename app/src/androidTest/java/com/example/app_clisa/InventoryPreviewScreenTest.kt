package com.example.app_clisa

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.data.excel.*
import com.example.app_clisa.ui.products.InventoryPreviewScreen
import com.example.app_clisa.ui.theme.AppclisaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An isolated Compose host, not MainActivity. All inventory is in memory; no Auth or Firestore calls. */
@RunWith(AndroidJUnit4::class)
class InventoryPreviewScreenTest {
    @get:Rule val ui = createComposeRule()
    private fun show(onClose: () -> Unit = {}) {
        val serial = ExcelSerial("0007", 2, "Excel", 3)
        val excel = ExcelInventory(listOf(ExcelProduct("SKU/001", "Parlante Creative", 2, listOf(serial), "Excel", 2, 1)), emptyList(), 0, 1)
        val snapshot = InventoryReadSnapshot(listOf(ComparedProduct("SKU_001", "SKU/001", "Parlante Creative", 3, 5)),
            listOf(ComparedExistence("entry-7", "SKU_001", "SKU/001", "0007", 1, "DISPONIBLE"),
                ComparedExistence("entry-missing", "SKU_001", "SKU/001", "0008", 2, "DISPONIBLE")), 1, 2)
        val comparison = InventoryComparator.compare(excel, snapshot)
        val preview = InventoryPreviewBuilder.build(comparison, excel)
        ui.setContent { AppclisaTheme { InventoryPreviewScreen(preview, comparison, excel,
            "Prueba local · sin Firestore.xlsx", onClose, {}) } }
        ui.waitForIdle()
    }
    @Test fun syncIsDisabledAndBackReturnsToCaller() {
        var closed = 0
        show { closed++ }
        ui.onNodeWithTag("preview-step4").assertIsNotEnabled().assertTextContains("Disponible en el paso 4")
        ui.onNodeWithTag("preview-back").performClick()
        ui.runOnIdle { assertEquals(1, closed) }
    }
    @Test fun mockupHasSixSummaryCardsAndProductTabsFilterLocally() {
        show()
        ui.onAllNodes(hasTestTag("preview-group-NEW_PRODUCTS") or hasTestTag("preview-group-NEW_EXISTENCES") or
            hasTestTag("preview-group-DIFFERENT_QUANTITIES") or hasTestTag("preview-group-ABSENT_EXISTENCES") or
            hasTestTag("preview-group-UNCHANGED") or hasTestTag("preview-group-CONFLICTS")).assertCountEquals(6)
        ui.onNodeWithTag("preview-tab-Nuevos").performScrollTo().performClick()
        ui.waitUntil { ui.onAllNodesWithTag("preview-product-product:SKU_001").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("preview-tab-Diferentes").performClick()
        ui.onNodeWithTag("preview-summary").performScrollToNode(hasTestTag("preview-product-product:SKU_001"))
        ui.onNodeWithTag("preview-product-product:SKU_001").assertExists()
        ui.onNodeWithText("Números de serie").assertExists()
        ui.onNodeWithText("Conservar").assertExists()
        ui.onNodeWithTag("preview-step4").assertIsNotEnabled()
    }
    @Test fun quantityDetailsKeepRealIdAndBackReturnsToOverview() {
        show()
        ui.onNodeWithTag("preview-group-DIFFERENT_QUANTITIES").performScrollTo().performClick()
        ui.waitUntil { ui.onAllNodesWithText("Serie: «0007»").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("ID real del producto: SKU_001").assertExists()
        ui.onNodeWithText("ID real de existencia: entry-7").assertExists()
        ui.onNodeWithText("Cantidad: 1 → 2").assertExists()
        ui.onNodeWithTag("preview-back").performClick()
        ui.onNodeWithText("Revisar sincronización").assertExists()
    }
    @Test fun absentSeriesAreConditionalAndLocalSearchFiltersThem() {
        show()
        ui.onNodeWithTag("preview-group-ABSENT_EXISTENCES").performScrollTo().performClick()
        ui.waitUntil { ui.onAllNodesWithText("Serie: «0008»").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Cantidad: 2 → sin definir").assertExists()
        ui.onNodeWithText("Ausencias pendientes").assertExists()
        ui.onNodeWithTag("preview-search").performTextInput("NO-EXISTE")
        ui.waitUntil { ui.onAllNodesWithText("Serie: «0008»").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("preview-step4").assertIsNotEnabled()
    }
    @Test fun captureOverviewUsingOnlyLocalFixtures() {
        show()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Public emulator output survives the test runner uninstalling its isolated host afterwards.
        fun capture(path: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p $path"))
                .use { it.readBytes() }
        }
        capture("/sdcard/Download/clisa-preview-step3-local.png")
        ui.onNodeWithTag("preview-group-DIFFERENT_QUANTITIES").performScrollTo().performClick()
        ui.waitUntil { ui.onAllNodesWithText("Serie: «0007»").fetchSemanticsNodes().isNotEmpty() }
        capture("/sdcard/Download/clisa-preview-step3-details-local.png")
    }
}
