package com.example.app_clisa

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.ui.products.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExcelClassificationPanelTest {
    @get:Rule val ui = createComposeRule()
    private fun open(complete: Boolean, missing: Boolean = false, pending: Int = 12, onContinue: () -> Unit = {}) {
        val counts=JSONObject().put("processed",850).put("byRules",608).put("byAi",230).put("pending",pending).put("excluded",0).put("rulesProcessed",620).put("aiProcessed",230)
        val job=JSONObject().put("status",if(complete)"PREVIEW" else "CLASSIFICATION").put("total",if(complete)850 else 1250).put("cursor",850)
            .put("review",JSONObject().put("validProducts",if(complete)850 else 1250)).put("preview",JSONObject().put("classificationPending",pending))
        if(!missing)job.put("classificationProgress",counts)
        ui.setContent {
            InventoryImportFrame("Clasificación de productos",{}) {
                InventorySelectionSteps(4,"Completando categoría, marca y descripción")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) { InventoryClassificationStage(job) }
                InventoryClassificationFooter(complete,onContinue)
            }
        }
    }
    private fun capture(name:String){ui.waitForIdle();Thread.sleep(250);ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")).use{it.readBytes()}}
    @Test fun realProgressKeepsContinueDisabled() {
        var clicks=0;open(false,onContinue={clicks++})
        ui.onNodeWithText("68%").assertExists();ui.onNodeWithText("850 de 1,250 productos").assertExists()
        ui.onNodeWithText("Resultados preliminares").assertExists();ui.onNodeWithText("608").assertExists()
        ui.onNodeWithText("Por IA").assertExists();ui.onNodeWithTag("classification-to-summary").assertIsNotEnabled().performClick()
        Assert.assertEquals(0,clicks);capture("classification-progress.png")
    }
    @Test fun pendingClassificationIsHonestAndContinuesToSummary() {
        var clicks=0;open(true,onContinue={clicks++})
        ui.onNodeWithText("Clasificación con información pendiente").assertExists()
        ui.onNodeWithText("100%").assertExists();ui.onNodeWithTag("classification-to-summary").assertIsEnabled().performClick()
        Assert.assertEquals(1,clicks);capture("classification-ready.png")
    }
    @Test fun legacyJobsNeverInventOriginCounts() {
        open(true,missing=true)
        ui.onAllNodesWithText("—").assertCountEquals(3)
        ui.onNodeWithText("El desglose por reglas e IA no está disponible para este expediente anterior.").assertExists()
    }
}
