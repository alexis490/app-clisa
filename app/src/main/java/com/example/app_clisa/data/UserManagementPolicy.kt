package com.example.app_clisa.data

internal object UserManagementPolicy {
    fun validateTargetRole(role: String?) {
        check(role in listOf("USER", "ADMIN")) { "Las cuentas SUPER_ADMIN o sin un rol válido no se modifican desde Android." }
    }
    fun canManage(claims: Map<String, Any?>): Boolean = claims["role"] == "SUPER_ADMIN" && claims["superAdmin"] == true
    fun validateStateChange(access: DirectoryAccess, targetId: String, next: String) {
        require(next in listOf("ACTIVO", "BLOQUEADO")) { "Estado de cuenta no válido." }
        check(access.canManage) { "Solo un Super Admin puede administrar cuentas." }
        check(access.uid != targetId) { "No puedes cambiar el estado de tu propia cuenta desde este panel." }
    }
}
