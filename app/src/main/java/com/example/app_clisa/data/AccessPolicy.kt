package com.example.app_clisa.data

enum class AppModule { REGISTER, RECORDS, PRODUCTS, REPORTS, USERS }

/** USER is the baseline for a token without role claims; elevation always needs signed claims. */
object AccessPolicy {
    private fun tokenRole(claims: Map<String, Any?>): String? {
        if (claims.containsKey("role")) return claims["role"] as? String
        // Incomplete administrative claims must never silently become a baseline account.
        if (claims.getOrDefault("admin", false) != false || claims.getOrDefault("superAdmin", false) != false) return null
        return "USER"
    }

    fun canCreateInitialProfile(claims: Map<String, Any?>): Boolean = tokenRole(claims) == "USER"

    fun verifiedRole(claims: Map<String, Any?>, profileRole: String?, state: String?): String? {
        val role = tokenRole(claims) ?: return null
        if (state != "ACTIVO" || profileRole != role) return null
        return when (role) {
            "USER" -> role
            "ADMIN" -> role.takeIf { claims["admin"] == true }
            "SUPER_ADMIN" -> role.takeIf { claims["admin"] == true && claims["superAdmin"] == true }
            else -> null
        }
    }

    fun allows(role: String?, module: AppModule): Boolean = when (role) {
        "USER" -> module == AppModule.REGISTER || module == AppModule.RECORDS
        "ADMIN" -> module != AppModule.USERS
        "SUPER_ADMIN" -> true
        else -> false
    }

    fun requireAccess(claims: Map<String, Any?>, profileRole: String?, state: String?, module: AppModule): String {
        val role = verifiedRole(claims, profileRole, state)
        check(allows(role, module)) { "Tu cuenta no está activa o no tiene permisos para esta operación. Revisa el rol del token y del perfil con el administrador." }
        return role!!
    }
}
