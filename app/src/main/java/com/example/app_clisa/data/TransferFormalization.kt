package com.example.app_clisa.data

enum class FormalizationState(val label: String) {
    PENDING("Pendiente"), COMPLETED("Completado"), UNVERIFIED("Sin verificar")
}

/** estado is the inventory movement state, never the external-system formalization state. */
object TransferFormalization {
    fun initialFields(): Map<String, Any?> = mapOf("estadoFormalizacion" to "PENDIENTE", "formalizadoEn" to null, "formalizadoPor" to null)
    fun state(fields: Map<String, Any?>): FormalizationState = when (fields["estadoFormalizacion"]) {
        "PENDIENTE" -> FormalizationState.PENDING
        "COMPLETADO" -> FormalizationState.COMPLETED
        else -> FormalizationState.UNVERIFIED
    }
    fun authorized(role: String?): Boolean = role in setOf("ADMIN", "SUPER_ADMIN")
    fun isTransfer(fields: Map<String, Any?>): Boolean =
        fields["tipo"] == "SALIDA" && fields["estado"] == "COMPLETADO" &&
            fields["tiendaOrigenId"] == "clisa" && fields["tiendaDestinoId"] == "toqen"
    fun confirmable(fields: Map<String, Any?>): Boolean = isTransfer(fields) &&
            (!fields.containsKey("estadoFormalizacion") || fields["estadoFormalizacion"] in setOf("PENDIENTE", "SIN_VERIFICAR")) &&
            fields["formalizadoEn"] == null && fields["formalizadoPor"] == null
    fun requireConfirmation(role: String?, fields: Map<String, Any?>) {
        check(authorized(role)) { "Tu cuenta no tiene permiso para confirmar traspasos." }
        check(confirmable(fields)) { "El registro ya fue completado o requiere revisión de sus datos." }
    }
}
