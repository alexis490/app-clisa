package com.example.app_clisa

import com.example.app_clisa.data.AccessPolicy
import com.example.app_clisa.data.AppModule
import com.example.app_clisa.data.UserManagementPolicy
import org.junit.Assert.*
import org.junit.Test

class AccessPolicyTest {
    private fun claims(role: String) = mapOf<String, Any?>(
        "role" to role, "admin" to (role != "USER"), "superAdmin" to (role == "SUPER_ADMIN"))

    @Test fun userOnlyHasMovementsAndHistory() {
        val role = AccessPolicy.verifiedRole(claims("USER"), "USER", "ACTIVO")
        assertEquals(setOf(AppModule.REGISTER, AppModule.RECORDS), AppModule.entries.filter { AccessPolicy.allows(role, it) }.toSet())
    }
    @Test fun adminHasCatalogAndReportsButNotUsers() {
        val role = AccessPolicy.verifiedRole(claims("ADMIN"), "ADMIN", "ACTIVO")
        assertEquals(AppModule.entries.toSet() - AppModule.USERS, AppModule.entries.filter { AccessPolicy.allows(role, it) }.toSet())
    }
    @Test fun superAdminHasEveryModule() {
        val role = AccessPolicy.verifiedRole(claims("SUPER_ADMIN"), "SUPER_ADMIN", "ACTIVO")
        assertTrue(AppModule.entries.all { AccessPolicy.allows(role, it) })
    }
    @Test fun blockedAndMissingStatesDenyEveryRole() {
        for (role in listOf("USER", "ADMIN", "SUPER_ADMIN")) for (state in listOf(null, "BLOQUEADO", "INACTIVO", "activo")) {
            val verified = AccessPolicy.verifiedRole(claims(role), role, state)
            assertTrue(AppModule.entries.none { AccessPolicy.allows(verified, it) })
        }
    }
    @Test fun profileCannotElevateOrKeepAnOldTokenRole() {
        assertNull(AccessPolicy.verifiedRole(claims("USER"), "SUPER_ADMIN", "ACTIVO"))
        assertNull(AccessPolicy.verifiedRole(claims("ADMIN"), "USER", "ACTIVO"))
        assertNull(AccessPolicy.verifiedRole(emptyMap(), "SUPER_ADMIN", "ACTIVO"))
        assertNull(AccessPolicy.verifiedRole(claims("USER"), null, "ACTIVO"))
    }
    @Test fun administrativeFlagsMustBeActualBooleans() {
        assertNull(AccessPolicy.verifiedRole(mapOf("role" to "ADMIN"), "ADMIN", "ACTIVO"))
        assertNull(AccessPolicy.verifiedRole(mapOf("role" to "ADMIN", "admin" to "true"), "ADMIN", "ACTIVO"))
        assertNull(AccessPolicy.verifiedRole(mapOf("role" to "SUPER_ADMIN", "superAdmin" to true), "SUPER_ADMIN", "ACTIVO"))
        assertNull(AccessPolicy.verifiedRole(mapOf("role" to "SUPER_ADMIN", "admin" to true), "SUPER_ADMIN", "ACTIVO"))
    }
    @Test fun unknownRolesAndUnauthenticatedRoutesAreDenied() {
        for (role in listOf(null, "", "OWNER", "admin")) assertTrue(AppModule.entries.none { AccessPolicy.allows(role, it) })
    }
    @Test(expected = IllegalStateException::class)
    fun directAdminCallCannotManageUsers() {
        AccessPolicy.requireAccess(claims("ADMIN"), "ADMIN", "ACTIVO", AppModule.USERS)
    }
    @Test(expected = IllegalStateException::class)
    fun directUserCallCannotAccessCatalog() {
        AccessPolicy.requireAccess(claims("USER"), "USER", "ACTIVO", AppModule.PRODUCTS)
    }
    @Test(expected = IllegalStateException::class)
    fun blockedAccountCannotSaveAMovement() {
        AccessPolicy.requireAccess(claims("USER"), "USER", "BLOQUEADO", AppModule.REGISTER)
    }
    @Test fun allActiveRolesCanSaveValidMovements() {
        for (role in listOf("USER", "ADMIN", "SUPER_ADMIN"))
            assertEquals(role, AccessPolicy.requireAccess(claims(role), role, "ACTIVO", AppModule.REGISTER))
    }
    @Test fun onlyOrdinaryTargetAccountsCanBeModified() {
        UserManagementPolicy.validateTargetRole("USER")
        UserManagementPolicy.validateTargetRole("ADMIN")
        for (role in listOf(null, "SUPER_ADMIN", "UNKNOWN")) {
            try { UserManagementPolicy.validateTargetRole(role); fail("Protected target: $role") }
            catch (_: IllegalStateException) { }
        }
    }
}
