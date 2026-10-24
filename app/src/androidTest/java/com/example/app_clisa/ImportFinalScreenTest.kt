package com.example.app_clisa

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.app_clisa.data.excel.*
import com.example.app_clisa.ui.products.ImportFinalScreen
import com.example.app_clisa.ui.theme.AppclisaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportFinalScreenTest {
    @get:Rule val ui = createComposeRule()
    @Test fun finalSummaryNeverClaimsExecutionAndRequiresOriginalForExport() {
        val excel = ExcelInventory(listOf(ExcelProduct("SKU", "Local", 1, listOf(ExcelSerial("001", 1, "Excel", 3)), "Excel", 2, 1)), emptyList(), 0, 1)
        val compared = InventoryComparator.compare(excel, InventoryReadSnapshot(emptyList(), emptyList(), 1, 2))
        val plan = InventoryDryRunPlanner.generate(compared, ReportReadEvidence.fromReadResult(excel, "test.xlsx", "ab".repeat(32)))
        ui.setContent { AppclisaTheme { ImportFinalScreen(plan, excel, compared, null, {}) } }
        ui.onNodeWithText("Sin ejecución simulada importada. El Dry Run no escribe documentos.").assertExists()
        ui.onNodeWithTag("import-final-summary").performScrollToNode(hasTestTag("final-sync"))
        ui.onNodeWithTag("final-sync").assertIsNotEnabled()
        ui.onNodeWithTag("import-final-summary").performScrollToNode(hasTestTag("final-export"))
        ui.onNodeWithTag("final-export").assertIsNotEnabled()
    }
}
