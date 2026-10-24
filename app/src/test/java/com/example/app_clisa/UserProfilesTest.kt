package com.example.app_clisa

import com.example.app_clisa.data.AccessPolicy
import com.example.app_clisa.data.AppModule
import com.example.app_clisa.data.InitialUserProfile
import org.junit.Assert.*
import org.junit.Test

class UserProfilesTest {
    private fun newProfile() = InitialUserProfile.data("uid", "user@example.com", " Nombre ", null, "server-time")

    @Test fun baselineAccountWithoutClaimsHasOnlyUserModules() {
        val role = AccessPolicy.verifiedRole(emptyMap(), "USER", "ACTIVO")
        assertEquals("USER", role)
        assertEquals(setOf(AppModule.REGISTER, AppModule.RECORDS), AppModule.entries.filter { AccessPolicy.allows(role, it) }.toSet())
    }
    @Test fun defaultRoleNeverElevatesAdministrativeProfiles() {
        assertNull(AccessPolicy.verifiedRole(emptyMap(), "ADMIN", "ACTIVO"))
        assertNull(AccessPolicy.verifiedRole(emptyMap(), "SUPER_ADMIN", "ACTIVO"))
        assertNull(AccessPolicy.verifiedRole(emptyMap(), null, "ACTIVO"))
    }
    @Test fun baselineAccountRemainsBlocked() {
        assertNull(AccessPolicy.verifiedRole(emptyMap(), "USER", "BLOQUEADO"))
        assertNull(AccessPolicy.verifiedRole(emptyMap(), "USER", null))
    }
    @Test fun malformedOrPartialClaimsDoNotFallBackToUser() {
        for (claims in listOf(mapOf("role" to null), mapOf("role" to "UNKNOWN"), mapOf("role" to 1),
            mapOf("role" to ""), mapOf("admin" to true), mapOf("superAdmin" to true), mapOf("admin" to "false"))) {
            assertFalse(AccessPolicy.canCreateInitialProfile(claims))
            assertNull(AccessPolicy.verifiedRole(claims, "USER", "ACTIVO"))
        }
    }
    @Test fun newProfileHasOnlyExistingFieldsAndFixedDefaults() {
        val profile = InitialUserProfile.resolve(null, emptyMap(), ::newProfile)
        assertEquals(setOf("uid", "email", "displayName", "role", "estado", "creadoEn"), profile.keys)
        assertEquals("USER", profile["role"])
        assertEquals("ACTIVO", profile["estado"])
        assertEquals("Nombre", profile["displayName"])
        assertEquals("user@example.com", profile["email"])
    }
    @Test fun optionalPhotoAndEmptyNameUseExistingSchema() {
        val profile = InitialUserProfile.data("uid", "user@example.com", " ", "https://example.com/photo", "time")
        assertEquals("Usuario", profile["displayName"])
        assertEquals("https://example.com/photo", profile["profileImageUri"])
    }
    @Test fun existingProfilesAreReturnedWithoutCallingCreationOrChangingAnyField() {
        for (role in listOf("USER", "ADMIN", "SUPER_ADMIN")) for (state in listOf("ACTIVO", "BLOQUEADO")) {
            val existing = mapOf<String, Any>("role" to role, "estado" to state, "storeName" to "CLISA", "custom" to 7)
            val result = InitialUserProfile.resolve(existing, emptyMap()) { fail("Existing profile must never be written"); newProfile() }
            assertSame(existing, result)
            assertEquals(state, result["estado"])
        }
    }
    @Test fun repeatedLoginKeepsTheFirstProfileUnchanged() {
        val first = InitialUserProfile.resolve(null, emptyMap(), ::newProfile)
        val second = InitialUserProfile.resolve(first, emptyMap()) { fail("Second login must not create a profile"); newProfile() }
        assertSame(first, second)
    }
    @Test fun administrativeAccountMissingProfileCannotBeDowngradedAutomatically() {
        for (role in listOf("ADMIN", "SUPER_ADMIN")) {
            try {
                InitialUserProfile.resolve(null, mapOf("role" to role, "admin" to true, "superAdmin" to true)) {
                    fail("Must not write USER over an administrative identity"); newProfile()
                }
                fail("Missing administrative profile must require intervention")
            } catch (_: IllegalStateException) { }
        }
    }
    @Test fun explicitlySignedUserCanAlsoInitializeItsProfile() {
        val profile = InitialUserProfile.resolve(null, mapOf("role" to "USER"), ::newProfile)
        assertEquals("USER", profile["role"])
    }
    @Test fun existingAdministrativeClaimsAndProfilesKeepTheirAccess() {
        assertEquals("ADMIN", AccessPolicy.verifiedRole(mapOf("role" to "ADMIN", "admin" to true), "ADMIN", "ACTIVO"))
        assertEquals("SUPER_ADMIN", AccessPolicy.verifiedRole(mapOf("role" to "SUPER_ADMIN", "admin" to true, "superAdmin" to true), "SUPER_ADMIN", "ACTIVO"))
    }
    @Test(expected = IllegalStateException::class)
    fun profileWithoutEmailCannotBeCreated() {
        InitialUserProfile.data("uid", null, "Nombre", null, "time")
    }
}
