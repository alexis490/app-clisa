package com.example.app_clisa

import com.example.app_clisa.data.*
import org.junit.Assert.*
import org.junit.Test

class TransferFormalizationTest {
    private val original=mapOf<String,Any?>("tipo" to "SALIDA","estado" to "COMPLETADO","tiendaOrigenId" to "clisa",
        "tiendaDestinoId" to "toqen","estadoPago" to "Pendiente","productoId" to "real-id","cantidad" to 1)
    @Test fun initialFieldsArePendingWithNullAuditAndSeparateFromPaymentAndMovement() {
        val fields=TransferFormalization.initialFields()
        assertEquals(setOf("estadoFormalizacion","formalizadoEn","formalizadoPor"),fields.keys)
        assertEquals("PENDIENTE",fields["estadoFormalizacion"]);assertNull(fields["formalizadoEn"]);assertNull(fields["formalizadoPor"])
        assertEquals("COMPLETADO",(original+fields)["estado"]);assertEquals("Pendiente",(original+fields)["estadoPago"])
    }
    @Test fun absentFieldIsUnverifiedNotPendingAndDoesNotMutateLegacyRecord() {
        assertEquals(FormalizationState.UNVERIFIED,TransferFormalization.state(original))
        assertTrue(TransferFormalization.confirmable(original));assertFalse(original.containsKey("estadoFormalizacion"))
    }
    @Test fun threeStatesAndUnknownDataAreReadConservatively() {
        assertEquals(FormalizationState.PENDING,TransferFormalization.state(original+TransferFormalization.initialFields()))
        assertEquals(FormalizationState.COMPLETED,TransferFormalization.state(original+mapOf("estadoFormalizacion" to "COMPLETADO")))
        for(v in listOf(null,"INVALID",123)) {
            val malformed=original+mapOf("estadoFormalizacion" to v)
            assertEquals(FormalizationState.UNVERIFIED,TransferFormalization.state(malformed))
            assertFalse(TransferFormalization.confirmable(malformed))
        }
    }
    @Test fun onlyAdminRolesCanConfirmAndUserCanReadButNotConfirm() {
        for(role in listOf(null,"USER","OTHER")) assertFalse(TransferFormalization.authorized(role))
        for(role in listOf("ADMIN","SUPER_ADMIN")) TransferFormalization.requireConfirmation(role,original)
        assertTrue(AccessPolicy.allows("USER",AppModule.RECORDS))
        assertThrows(IllegalStateException::class.java) { TransferFormalization.requireConfirmation("USER",original) }
    }
    @Test fun blockedOrMismatchedProfileCannotYieldAuthorizedRole() {
        val claims=mapOf("role" to "ADMIN","admin" to true)
        assertNull(AccessPolicy.verifiedRole(claims,"ADMIN","BLOQUEADO"))
        assertNull(AccessPolicy.verifiedRole(claims,"USER","ACTIVO"))
        assertFalse(TransferFormalization.authorized(AccessPolicy.verifiedRole(claims,"ADMIN","BLOQUEADO")))
    }
    @Test fun completedOrExistingAuditNeverConfirmableAgain() {
        for(extra in listOf(mapOf("estadoFormalizacion" to "COMPLETADO"), mapOf("formalizadoPor" to "someone"),mapOf("formalizadoEn" to "date"))) {
            assertFalse(TransferFormalization.confirmable(original+extra))
            assertThrows(IllegalStateException::class.java) { TransferFormalization.requireConfirmation("ADMIN",original+extra) }
        }
    }
    @Test fun onlyClisaToToqenCompletedSalidasQualify() {
        for(extra in listOf(mapOf("tipo" to "AJUSTE_ENTRADA"),mapOf("estado" to "ANULADO"),mapOf("tiendaOrigenId" to "other"),mapOf("tiendaDestinoId" to "other")))
            assertFalse(TransferFormalization.confirmable(original+extra))
    }
    @Test fun explicitUnverifiedLegacyStateIsConfirmableWithoutInitialMigration() {
        assertTrue(TransferFormalization.confirmable(original+mapOf("estadoFormalizacion" to "SIN_VERIFICAR")))
    }
}
