package com.example.app_clisa

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.app_clisa.data.excel.*
import com.example.app_clisa.ui.products.ProductClassificationScreen
import com.example.app_clisa.ui.theme.AppclisaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fictitious references and callbacks; no Firebase connection. */
@RunWith(AndroidJUnit4::class)
class ProductClassificationUiTest {
    @get:Rule val ui = createComposeRule()
    private val hash = "ab".repeat(32)
    private val rules = ClassificationRules.read("version\tTEST\ncategory\tParlantes y audio\tparlante\ncategory\tAudífonos\tauriculares\n")
    private val catalog = ClassificationCatalog(listOf(ClassificationReference("audio-real", "Parlantes y audio", true),
        ClassificationReference("headset-real", "Audífonos", true)), listOf(ClassificationReference("creative-real", "Creative", true)))
    private val p = ClassificationProduct("SKU", "REAL-ID", "SKU", "Parlante Creative", null, null, null, true)
    private fun review(product: ClassificationProduct = p) = ClassificationReview(hash, listOf(product), catalog, rules)
    @Test fun catalogLoadIsExplicitAndNotRepeatedByRendering() {
        var loads = 0
        ui.setContent { AppclisaTheme { ProductClassificationScreen(null, null, false, null, {}, { loads++ }) { fail("Sin revisión") } } }
        ui.runOnIdle { assertEquals(0, loads) }; ui.onNodeWithTag("classification-read").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(1, loads) }
    }
    @Test fun bulkRequiresConfirmationAndContainsOnlyUnequivocalFields() {
        var saved: List<ClassificationChoice>? = null
        ui.setContent { AppclisaTheme { ProductClassificationScreen(review(p.copy(name = "Parlante auriculares Creative")), IssueReviewJournal(hash), false, null, {}, {}) { saved = it } } }
        ui.onNodeWithText("Aprobar inequívocas (1)").performScrollTo().performClick()
        ui.runOnIdle { assertNull(saved) }; ui.onNodeWithText("Confirmar aprobaciones").performClick()
        ui.runOnIdle { assertEquals("marcaId", saved!!.single().field); assertTrue(saved!!.single().bulk) }
    }
    @Test fun individualReviewNeedsReferenceReasonAndConfirmation() {
        var saved: List<ClassificationChoice>? = null
        ui.setContent { AppclisaTheme { ProductClassificationScreen(review(p.copy(name = "Objeto genérico")), IssueReviewJournal(hash), false, null, {}, {}) { saved = it } } }
        ui.onNodeWithTag("classification-edit-SKU-categoriaId").performScrollTo().performClick()
        ui.onNodeWithText("Revisar aprobación").assertIsNotEnabled()
        ui.onNodeWithText("Elegir referencia existente").performClick(); ui.onNodeWithText("Parlantes y audio (audio-real)").performClick()
        ui.onNodeWithTag("classification-reason").performTextInput("Verificado contra ficha técnica")
        ui.onNodeWithText("Revisar aprobación").performClick(); ui.runOnIdle { assertNull(saved) }
        ui.onNodeWithText("Confirmar aprobaciones").performClick()
        ui.runOnIdle { assertEquals("audio-real", saved!!.single().id); assertFalse(saved!!.single().bulk) }
    }
    @Test fun validFieldsStayLockedAndDescriptionIsNotGenerated() {
        ui.setContent { AppclisaTheme { ProductClassificationScreen(review(p.copy(categoryId = "audio-real", brandId = "creative-real")), IssueReviewJournal(hash), false, null, {}, {}) { fail("No sobrescribir") } } }
        ui.onNodeWithTag("classification-edit-SKU-categoriaId").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("Descripción pendiente", substring = true).performScrollTo().assertExists()
        ui.onNodeWithTag("classification-bulk").performScrollTo().assertIsNotEnabled()
    }
    @Test fun unverifiedRoleCannotApproveFromDirectScreen() {
        ui.setContent { AppclisaTheme { ProductClassificationScreen(review(), IssueReviewJournal(hash), false, null, {}, {}, false) { fail("USER no aprueba") } } }
        ui.onNodeWithTag("classification-bulk").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("classification-edit-SKU-categoriaId").performScrollTo().assertIsNotEnabled()
    }
}
