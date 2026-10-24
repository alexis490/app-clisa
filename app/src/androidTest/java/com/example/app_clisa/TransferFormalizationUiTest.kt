package com.example.app_clisa

import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app_clisa.data.FormalizationState
import com.example.app_clisa.ui.records.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.io.File

/** Local fixture UI only. No MainActivity, Authentication session, Firestore instance or real record. */
@RunWith(AndroidJUnit4::class)
class TransferFormalizationUiTest {
    @get:Rule val ui=createComposeRule()
    private fun record(state: FormalizationState=FormalizationState.PENDING)=ExitRecord("local-only",
        "Laptop de demostración local con nombre largo, procesador, memoria y almacenamiento para probar la distribución sin recortar información",
        "SKU-LOCAL-001",1,"Operador local","Receptor local","TOQEN","SERIE-LOCAL",Instant.parse("2026-10-22T15:00:00Z"),
        "Pendiente",null,state,state!=FormalizationState.COMPLETED)
    @Composable private fun Frame(content:@Composable ()->Unit) {
        MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF0061FF),onSurface=Color(0xFFF1F5FF))) {
            Surface(Modifier.fillMaxSize(),color=Color(0xFF020E21),contentColor=Color(0xFFF1F5FF)) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(12.dp).verticalScroll(rememberScrollState())) { content() }
            }
        }
    }
    private fun capture(name:String) {
        ui.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$name")).use { it.readBytes() }
    }
    @Test fun threeBadgesDisplayPendingCompletedAndLegacyUnverified() {
        ui.setContent { Frame { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            FormalizationState.entries.forEach { s->FormalizationBadge(s,false,s.name){} }
        } } }
        for(s in FormalizationState.entries)ui.onNodeWithText(s.label).assertExists()
        val image=ui.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"clisa-formal-three-states.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun pendingBadgeOpensConfirmationWithoutOpeningDetailAndCancelDoesNotConfirm() {
        var show by mutableStateOf(false);var details=0;var confirms=0
        val r=record()
        ui.setContent { Frame {
            RecordCard(r,true,{show=true},{details++})
            if(show)FormalizationDialog(r.product,r.code,false,null,{show=false},{confirms++})
        } }
        capture("clisa-formal-pending.png")
        ui.onNodeWithTag("formal-status-local-only").performClick()
        ui.onNodeWithText("¿Confirmas que este traspaso ya fue registrado en el sistema principal?").assertExists()
        capture("clisa-formal-dialog.png")
        ui.onNodeWithTag("formal-cancel").performScrollTo().performClick()
        ui.runOnIdle {assertFalse(show);assertEquals(0,details);assertEquals(0,confirms)}
    }
    @Test fun busyPreventsRepeatedConfirmationAndErrorLeavesBadgePending() {
        var busy by mutableStateOf(false);var error by mutableStateOf<String?>(null);var calls=0
        val r=record()
        ui.setContent {Frame {
            RecordCard(r,false,{}, {})
            FormalizationDialog(r.product,r.code,busy,error,{}, {calls++;busy=true})
        } }
        ui.onNodeWithTag("formal-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("formal-confirm").assertIsNotEnabled();ui.onNodeWithTag("formal-cancel").assertIsNotEnabled()
        ui.runOnIdle {assertEquals(1,calls);busy=false;error="No se pudo confirmar. Revisa tu conexión."}
        ui.onNodeWithTag("formal-error").performScrollTo().assertExists()
        ui.onNodeWithTag("formal-status-local-only",useUnmergedTree=true).assertExists()
        ui.runOnIdle {assertEquals(FormalizationState.PENDING,r.formalization)}
    }
    @Test fun readonlyBadgeDoesNotRemoveNormalDetailNavigation() {
        var detail=0;var confirmations=0
        ui.setContent {Frame {RecordCard(record(),false,{confirmations++},{detail++})} }
        ui.onNodeWithTag("formal-status-local-only").assertIsNotEnabled()
        ui.onNodeWithTag("record-local-only").performClick()
        ui.runOnIdle {assertEquals(1,detail);assertEquals(0,confirmations)}
    }
    @Test fun completedIsReadonlyAndFitsSmallWidthWithLongProductName() {
        ui.setContent {Frame {Box(Modifier.width(300.dp)) {
            RecordCard(record(FormalizationState.COMPLETED),false,{fail("No second confirmation")},{})
        }} }
        ui.onNodeWithTag("formal-status-local-only").assertIsNotEnabled()
        ui.onNodeWithText(record().product).assertExists()
        ui.onNodeWithText("Estado de pago",substring=true).assertDoesNotExist() // Existing payment remains in detail.
        capture("clisa-formal-completed-small.png")
    }
}
