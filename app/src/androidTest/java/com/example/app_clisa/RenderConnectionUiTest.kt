package com.example.app_clisa

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.app_clisa.ui.products.RenderImportButtons
import com.example.app_clisa.ui.theme.AppclisaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderConnectionUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun connectionIsExplicitAndPreparationRequiresAValidSource() {
        var checked = 0
        ui.setContent { AppclisaTheme { androidx.compose.foundation.layout.Column {
            RenderImportButtons(false, false, false, null, { checked++ }, { fail("Sin origen válido") }, {})
        } } }
        ui.runOnIdle { assertEquals(0, checked) }
        ui.onNodeWithTag("render-prepare").assertIsNotEnabled()
        ui.onNodeWithTag("render-check").performClick(); ui.runOnIdle { assertEquals(1, checked) }
    }
    @Test fun connectionOnlyDoesNotRequireReadingExcelOrInventory() {
        var checked = 0
        ui.setContent { AppclisaTheme { androidx.compose.foundation.layout.Column {
            RenderImportButtons(false, false, false, null, { checked++ }, { fail() }, {}, onlyCheck = true)
        } } }
        ui.onNodeWithTag("render-prepare").assertDoesNotExist()
        ui.onNodeWithTag("render-check").performClick(); ui.runOnIdle { assertEquals(1, checked) }
    }
    @Test fun busyRequestsCannotBeDuplicatedAndCanBeCancelled() {
        var cancelled = 0
        ui.setContent { AppclisaTheme { androidx.compose.foundation.layout.Column {
            RenderImportButtons(true, false, true, "Conectando…", { fail() }, { fail() }, { cancelled++ })
        } } }
        ui.onNodeWithTag("render-check").assertIsNotEnabled(); ui.onNodeWithTag("render-prepare").assertIsNotEnabled()
        ui.onNodeWithTag("render-cancel").performClick(); ui.runOnIdle { assertEquals(1, cancelled) }
    }
    @Test fun preparedResponseStillExplainsNoExecution() {
        ui.setContent { AppclisaTheme { androidx.compose.foundation.layout.Column {
            RenderImportButtons(false, true, true, "Propuesta preparada. No se ejecutaron cambios.", {}, {}, {})
        } } }
        ui.onNodeWithTag("render-result").assertTextContains("No se ejecutaron cambios.", substring = true)
        ui.onNodeWithTag("render-check").assertIsNotEnabled(); ui.onNodeWithTag("render-prepare").assertIsNotEnabled()
    }
}
