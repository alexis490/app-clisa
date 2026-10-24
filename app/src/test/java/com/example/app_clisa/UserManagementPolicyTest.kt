package com.example.app_clisa

import com.example.app_clisa.data.DirectoryAccess
import com.example.app_clisa.data.UserManagementPolicy
import org.junit.Assert.*
import org.junit.Test

class UserManagementPolicyTest {
    @Test fun requiresBothSuperAdminClaimsFromTheToken() {
        assertFalse(UserManagementPolicy.canManage(mapOf("role" to "SUPER_ADMIN")))
        assertFalse(UserManagementPolicy.canManage(mapOf("role" to "ADMIN", "superAdmin" to true)))
        assertFalse(UserManagementPolicy.canManage(mapOf("role" to "SUPER_ADMIN", "superAdmin" to "true")))
        assertTrue(UserManagementPolicy.canManage(mapOf("role" to "SUPER_ADMIN", "superAdmin" to true)))
    }
    @Test(expected = IllegalStateException::class)
    fun doesNotAllowSelfBlocking() {
        UserManagementPolicy.validateStateChange(DirectoryAccess("owner", true), "owner", "BLOQUEADO")
    }
    @Test(expected = IllegalStateException::class)
    fun preventsAdministrativeWritesForOrdinaryUsers() {
        UserManagementPolicy.validateStateChange(DirectoryAccess("owner", false), "other", "ACTIVO")
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownStatusValues() {
        UserManagementPolicy.validateStateChange(DirectoryAccess("owner", true), "other", "ADMIN")
    }
    @Test fun allowsExplicitStatusChangesForAnotherAccount() {
        UserManagementPolicy.validateStateChange(DirectoryAccess("owner", true), "other", "ACTIVO")
        UserManagementPolicy.validateStateChange(DirectoryAccess("owner", true), "other", "BLOQUEADO")
    }
}
