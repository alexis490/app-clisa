package com.example.app_clisa

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.app_clisa.data.excel.*
import com.example.app_clisa.ui.products.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local projections only: no queries or formalization verification in Importar Excel. */
@RunWith(AndroidJUnit4::class)
class ExportTimeSectionTest {
    @get:Rule val ui=createComposeRule()
    private val report=ExcelInventory(listOf(ExcelProduct("S","Local",0,emptyList(),"Excel",2,0)),emptyList(),0,1,
        ExcelStructure(true,1,1,2,1,1,0,0,0,0))
    @Test fun safetyExplainsExcelReferenceAndHasNoHistoricalQuery() {
        ui.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ExportSafetyReview(report,ExcelCompletenessValidator.assess(report,null),null)
            }
        } }
        ui.onNodeWithText("El stock propuesto suma las cantidades válidas del Excel. No se descuentan traspasos históricos.").assertExists()
        ui.onNodeWithTag("export-review-history").assertDoesNotExist()
        ui.onNodeWithTag("export-date").assertDoesNotExist()
        ui.onNodeWithText("Revisar conciliación").assertDoesNotExist()
    }
    @Test fun incompleteReportNeverClaimsCompleteOrEnablesWrites() {
        val invalid=report.copy(errorCount=1)
        ui.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ExportSafetyReview(invalid,ExcelCompletenessValidator.assess(invalid,null),null)
            }
        } }
        ui.onNodeWithText("Hay incidencias o comprobaciones técnicas pendientes. Revisa las filas de origen.").assertExists()
        ui.onNodeWithText("Productos y existencias ausentes se conservan sin cambios. No se habilitan eliminaciones automáticas.").assertExists()
    }
    @Test fun actualImportEntryDoesNotOfferReconciliationAndStillNeedsFile() {
        ui.setContent { MaterialTheme(colorScheme=darkColorScheme()) { ExcelImportAction() } }
        ui.onNodeWithText("Importar Excel").performClick()
        ui.onNodeWithTag("open-reconciliation").assertDoesNotExist()
        ui.onNodeWithTag("export-date").assertDoesNotExist()
        ui.onNodeWithTag("import-analyze").assertIsNotEnabled()
    }
}
