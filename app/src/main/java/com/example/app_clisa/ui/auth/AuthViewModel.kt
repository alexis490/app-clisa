package com.example.app_clisa.ui.auth

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.example.app_clisa.data.AccessPolicy
import com.example.app_clisa.data.AppModule
import com.example.app_clisa.data.UserProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SignedInProfile(val uid: String, val name: String, val role: String)
data class AuthUiState(val busy: Boolean = false, val profile: SignedInProfile? = null, val message: String? = null)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val profiles = UserProfiles()
    private val preferences = application.getSharedPreferences("clisa_session", Context.MODE_PRIVATE)
    private var profileListener: ListenerRegistration? = null
    private var monitoredUid: String? = null
    var state by mutableStateOf(AuthUiState())
        private set
    private val tokenListener = FirebaseAuth.IdTokenListener { source ->
        val profile = state.profile
        if (profile != null) {
            val user = source.currentUser
            if (user?.uid != profile.uid) invalidateSession("La sesión cambió. Vuelve a iniciar sesión.")
            else viewModelScope.launch {
                try {
                    val claims = user.getIdToken(false).result().claims
                    if (state.profile == profile && AccessPolicy.verifiedRole(claims, profile.role, "ACTIVO") == null)
                        invalidateSession("Los permisos de tu sesión cambiaron. Vuelve a iniciar sesión.")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (state.profile == profile) invalidateSession("No se pudieron verificar tus permisos. Vuelve a iniciar sesión.")
                }
            }
        }
    }

    init {
        auth.addIdTokenListener(tokenListener)
        if (!preferences.getBoolean("remember", false)) auth.signOut()
        else if (auth.currentUser != null) execute { checkAccess() }
    }

    private fun execute(block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: GetCredentialCancellationException) { /* User closed the account picker. */ }
            catch (e: Exception) { stopMonitoring(); state = state.copy(profile = null, message = readableError(e)) }
            finally { state = state.copy(busy = false) }
        }
    }

    fun login(email: String, password: String, remember: Boolean) = execute {
        auth.signInWithEmailAndPassword(email.trim(), password).result()
        preferences.edit().putBoolean("remember", remember).apply()
        checkAccess()
    }

    fun register(name: String, email: String, password: String) = execute {
        val user = auth.createUserWithEmailAndPassword(email.trim(), password).result().user ?: error("No se pudo crear la cuenta.")
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).result()
        preferences.edit().putBoolean("remember", false).apply()
        checkAccess()
    }

    fun google(context: Context, remember: Boolean) = execute {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        check(id != 0) { "Falta la configuración de Google. Actualiza google-services.json desde Firebase." }
        // Explicit button flow also supports accounts needing re-authentication
        // and lets the user add an account when the bottom sheet has none to offer.
        val option = GetSignInWithGoogleOption.Builder(context.getString(id)).build()
        val credential = CredentialManager.create(context).getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "No se recibió una cuenta válida de Google." }
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).result()
        preferences.edit().putBoolean("remember", remember).apply()
        checkAccess()
    }

    private suspend fun checkAccess() {
        val user = auth.currentUser ?: error("Vuelve a iniciar sesión.")
        val claims = user.getIdToken(true).result().claims
        val document = profiles.ensure(user, claims)
        check(document["estado"] == "ACTIVO") { "Tu cuenta está bloqueada. Contacta al administrador." }
        val role = AccessPolicy.requireAccess(claims, document["role"] as? String, document["estado"] as? String, AppModule.RECORDS)
        check(auth.currentUser?.uid == user.uid) { "La sesión cambió. Vuelve a iniciar sesión." }
        state = state.copy(profile = SignedInProfile(user.uid, document["displayName"] as? String ?: user.displayName ?: "Usuario", role), message = null)
        if (monitoredUid != user.uid) {
            stopMonitoring()
            monitoredUid = user.uid
            // Only the authenticated profile, never the entire users collection.
            profileListener = db.collection("usuarios").document(user.uid).addSnapshotListener { snapshot, error ->
                if (monitoredUid != user.uid) return@addSnapshotListener
                if (error != null) {
                    invalidateSession("No se pudo verificar el acceso a tu cuenta. Vuelve a iniciar sesión.")
                } else if (snapshot != null && !snapshot.metadata.isFromCache) {
                    if (!snapshot.exists() || snapshot.getString("estado") != "ACTIVO" || snapshot.getString("role") != state.profile?.role) {
                        invalidateSession("El estado o los permisos de tu cuenta cambiaron. Vuelve a iniciar sesión.")
                    }
                }
            }
        }
    }

    fun refreshAccess() {
        if (state.profile != null) execute { checkAccess() }
    }

    fun authorize(module: AppModule, onAllowed: () -> Unit) = execute {
        checkAccess()
        check(AccessPolicy.allows(state.profile?.role, module)) { "Tu rol no tiene acceso a este módulo." }
        onAllowed()
    }

    private fun stopMonitoring() {
        monitoredUid = null
        profileListener?.remove()
        profileListener = null
    }

    private fun invalidateSession(message: String) {
        stopMonitoring()
        if (auth.currentUser != null) auth.signOut()
        state = AuthUiState(message = message)
    }

    override fun onCleared() {
        stopMonitoring()
        auth.removeIdTokenListener(tokenListener)
        super.onCleared()
    }

    fun recover(email: String) = execute {
        auth.sendPasswordResetEmail(email.trim()).result()
        state = state.copy(message = "Si el correo corresponde a una cuenta que permite recuperación, recibirás un enlace para cambiar tu contraseña.")
    }

    fun clearMessage() { state = state.copy(message = null) }

    fun signOut() {
        stopMonitoring()
        auth.signOut()
        preferences.edit().putBoolean("remember", false).apply()
        state = AuthUiState()
        viewModelScope.launch {
            try { CredentialManager.create(getApplication<Application>()).clearCredentialState(ClearCredentialStateRequest()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Firebase session has already been cleared. */ }
        }
    }

    private fun readableError(error: Exception): String = when (error) {
        is FirebaseAuthUserCollisionException -> "Ese correo ya tiene una cuenta. Inicia sesión con el método que utilizaste al registrarte."
        is FirebaseAuthWeakPasswordException -> "La contraseña no cumple los requisitos de Firebase. Usa una contraseña más segura."
        is FirebaseAuthInvalidCredentialsException -> "Revisa tu correo y contraseña o utiliza Google si tu cuenta se creó con Google."
        is FirebaseAuthInvalidUserException -> "No se pudo acceder a esta cuenta. Revisa los datos o contacta al administrador."
        is FirebaseNetworkException -> "No hay conexión con Firebase. Revisa tu conexión e inténtalo otra vez."
        is NoCredentialException -> "No se pudo obtener una cuenta para continuar con Google. Revisa las cuentas de Google en los ajustes de Android y vuelve a intentarlo."
        is FirebaseFirestoreException -> if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "Firestore rechazó la verificación del perfil. Las reglas deben permitir leer tu documento propio, crearlo como USER si falta y acceder como USER activo sin exigir un custom claim inicial. Los perfiles existentes no se sobrescriben." else "No se pudo consultar Firebase. Inténtalo nuevamente."
        is IllegalStateException -> error.message ?: "No se pudo completar la operación."
        else -> "No se pudo completar el acceso. Revisa la conexión y la configuración de Firebase."
    }
}

private suspend fun <T> Task<T>.result(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
