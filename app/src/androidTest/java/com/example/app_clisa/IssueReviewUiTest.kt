package com.example.app_clisa

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.data.excel.*
import com.example.app_clisa.ui.products.IssueReviewScreen
import com.example.app_clisa.ui.theme.AppclisaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pure Compose callbacks and fictional data: never opens Firebase. */
@RunWith(AndroidJUnit4::class)
class IssueReviewUiTest {
    @get:Rule val ui = createComposeRule()
    private val hash = "ab".repeat(32)
    private val excel = ExcelInventory(listOf(ExcelProduct("SKU", "Producto local", -1,
        listOf(ExcelSerial("0001", -1, "Excel", 3)), "Excel", 2, 1)),
        listOf(ExcelIssue("Excel", 3, "Cantidad negativa", ExcelIssueKind.NEGATIVE_QUANTITY, "SKU", "Producto local", "0001")), 1, 1)
    private val catalog = IssueReviewCatalog(hash, excel)
    @Test fun adminRequiresExplicitConfirmationAndPreservesOriginal() {
        var saved = 0
        ui.setContent { AppclisaTheme { IssueReviewScreen(catalog, IssueReviewJournal(hash), true, onBack = {}) { _, p, r, e, _ ->
            assertEquals("2", p); assertEquals("Saldo revisado", r); assertEquals("ticket123", e); saved++
        } } }
        ui.onNodeWithTag("issue-review-list").captureToImage().asAndroidBitmap().let { bitmap ->
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "issue-review.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        ui.onNodeWithText("NEGATIVE_QUANTITY · PENDIENTE").performScrollTo().performClick()
        ui.onNodeWithTag("issue-proposal").performScrollTo().performTextInput("2")
        ui.onNodeWithTag("issue-reason").performScrollTo().performTextInput("Saldo revisado")
        ui.onNodeWithTag("issue-evidence").performScrollTo().performTextInput("ticket123")
        ui.onNodeWithTag("issue-save").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(0, saved) }
        ui.onNodeWithText("Confirmar decisión").performClick()
        ui.runOnIdle { assertEquals(1, saved); assertEquals(-1L, excel.products.single().serials.single().quantity) }
    }
    @Test fun userCanReviewButCannotSaveOrPropose() {
        ui.setContent { AppclisaTheme { IssueReviewScreen(catalog, IssueReviewJournal(hash), false, onBack = {}) { _, _, _, _, _ -> fail("USER must not save") } } }
        ui.onNodeWithText("NEGATIVE_QUANTITY · PENDIENTE").performScrollTo().performClick()
        ui.onNodeWithTag("issue-save").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("issue-proposal").performScrollTo().assertIsNotEnabled()
    }
    @Test fun copySummaryIsReadOnlyAndShowsWriterBlock() {
        ui.setContent { AppclisaTheme { IssueReviewScreen(catalog, IssueReviewJournal(hash), true, onBack = {}) { _, _, _, _, _ -> fail("No write") } } }
        ui.onNodeWithText("Revisar propuesta de copia").performScrollTo().performClick()
        ui.onNodeWithText("Resumen antes de generar").assertExists()
        ui.onNodeWithText("Copia XLSX bloqueada", substring = true).assertExists()
    }
    @Test fun statesAreRecoveredAndFiltersAreLocal() {
        val item = catalog.items.single()
        val decision = IssueDecision(item.id, item.issue.kind, "-1", "", "Pendiente de evidencia", "", "admin", 1, IssueReviewState.BLOQUEADO, hash)
        ui.setContent { AppclisaTheme { IssueReviewScreen(catalog, IssueReviewJournal(hash, listOf(decision)), true, onBack = {}) { _, _, _, _, _ -> fail("No write") } } }
        ui.onNodeWithText("NEGATIVE_QUANTITY · BLOQUEADO").performScrollTo().assertExists()
        ui.onNodeWithText("Estado: Todos").performScrollTo().performClick()
        ui.onNodeWithText("RESUELTO").performClick()
        ui.onNodeWithText("NEGATIVE_QUANTITY · BLOQUEADO").assertDoesNotExist()
    }
}
