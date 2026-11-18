package com.example.app_clisa.data.backend

import com.example.app_clisa.data.inventoryAwait
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import kotlinx.coroutines.CancellationException

internal class BackendCredential(val uid: String, val token: String) {
    override fun toString() = "BackendCredential([redacted])"
}
internal interface BackendSession {
    fun currentUid(): String?
    suspend fun credential(forceRefresh: Boolean): BackendCredential
}
internal object BackendRolePolicy {
    fun requireAdmin(claims: Map<String, Any?>) {
        val role = claims["role"]
        if (role !in setOf("ADMIN", "SUPER_ADMIN") || claims["admin"] != true ||
            (role == "SUPER_ADMIN" && claims["superAdmin"] != true)) throw BackendFailure(BackendProblem.FORBIDDEN)
    }
}
internal class FirebaseBackendSession(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) : BackendSession {
    override fun currentUid() = auth.currentUser?.uid
    override suspend fun credential(forceRefresh: Boolean): BackendCredential {
        val user = auth.currentUser ?: throw BackendFailure(BackendProblem.SESSION)
        try {
            val result = user.getIdToken(forceRefresh).inventoryAwait()
            if (auth.currentUser?.uid != user.uid) throw BackendFailure(BackendProblem.SESSION)
            // Early rejection only. The API independently verifies current Auth claims and active Firestore profile.
            BackendRolePolicy.requireAdmin(result.claims)
            return BackendCredential(user.uid, result.token?.takeIf { it.isNotBlank() } ?: throw BackendFailure(BackendProblem.SESSION))
        } catch (e: CancellationException) { throw e }
        catch (e: BackendFailure) { throw e }
        catch (_: FirebaseNetworkException) { throw BackendFailure(BackendProblem.NETWORK) }
        catch (_: FirebaseTooManyRequestsException) { throw BackendFailure(BackendProblem.RATE_LIMIT) }
        catch (e: FirebaseAuthInvalidUserException) { throw BackendFailure(if (e.errorCode == "ERROR_USER_DISABLED") BackendProblem.FORBIDDEN else BackendProblem.SESSION) }
        catch (_: Exception) { throw BackendFailure(BackendProblem.SESSION) }
    }
}
