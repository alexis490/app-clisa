package com.example.app_clisa

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Static checks of the downloaded evidence, NOT proof that published rules authorize a future executor. */
class ImportRulesAuditTest {
    private val source: String by lazy {
        listOf(File("security/audit-2026-10-21/deployed-0-firestore.rules"),
            File("../security/audit-2026-10-21/deployed-0-firestore.rules")).first { it.isFile }.readText()
    }
    private fun function(name: String): String {
        val start = Regex("function\\s+$name\\s*\\(").find(source)!!.range.first
        val next = Regex("function\\s+\\w+\\s*\\(").find(source, start + "function $name(".length)?.range?.first ?: source.length
        return source.substring(start, next)
    }
    private fun fields(text: String, method: String): Set<String> {
        val array = Regex("keys\\(\\)\\s*\\.\\s*$method\\s*\\(\\s*\\[([\\s\\S]*?)]").find(text)!!.groupValues[1]
        return Regex("\"([^\"]+)\"").findAll(array).map { it.groupValues[1] }.toSet()
    }
    @Test fun detectsSalidaPaymentContradictionInsteadOfAssumingWritesWork() {
        val section = function("registroSalidaValido")
        assertTrue(section.contains("r.estadoPago"))
        assertFalse("estadoPago" in fields(section, "hasOnly"))
    }
    @Test fun detectsRequiredButForbiddenAdjustmentFields() {
        val section = function("registroAjusteValido")
        assertEquals(setOf("recibidoPor", "estadoPago"), fields(section, "hasAll") - fields(section, "hasOnly"))
    }
    @Test fun productCreationContractCannotBeMistakenForAFullImportSchema() {
        val section = function("nuevoProductoValido")
        assertTrue(Regex("stock\\s*==\\s*0").containsMatchIn(section))
        assertFalse("categoriaId" in fields(section, "hasOnly"))
        assertFalse("cantidadRestante" in fields(section, "hasOnly"))
    }
    @Test fun activeProfileAndTokenRoleMustAgree() {
        val section = function("cuentaActiva")
        assertTrue(section.contains("rolValido()")); assertTrue(section.contains("estado == \"ACTIVO\""))
        assertTrue(section.contains("role == rolEfectivo()"))
    }
    @Test fun importSummaryIsCurrentlyCompletedAndImmutableNotAResumableJob() {
        assertTrue(function("importacionValida").contains("i.estado == \"COMPLETADO\""))
        val section = source.substringAfter("match /importaciones/{importacionId}").substringBefore("match /receptores/")
        assertTrue(section.contains("allow update, delete: if false"))
    }
}
