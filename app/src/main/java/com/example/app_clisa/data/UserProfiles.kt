package com.example.app_clisa.data

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

internal object InitialUserProfile {
    fun data(uid: String, email: String?, name: String?, photo: String?, createdAt: Any): Map<String, Any> {
        require(uid.isNotBlank()) { "No se recibió un usuario válido." }
        val address = email?.takeIf { it.isNotBlank() } ?: error("La cuenta no tiene correo.")
        return buildMap {
            put("uid", uid)
            put("email", address)
            put("displayName", name?.trim()?.takeIf { it.isNotEmpty() } ?: "Usuario")
            put("role", "USER")
            put("estado", "ACTIVO")
            put("creadoEn", createdAt)
            photo?.takeIf { it.isNotBlank() }?.let { put("profileImageUri", it) }
        }
    }

    /** Existing data is returned unchanged, including blocked and administrative profiles. */
    fun resolve(existing: Map<String, Any>?, claims: Map<String, Any?>, create: () -> Map<String, Any>): Map<String, Any> {
        if (existing != null) return existing
        check(AccessPolicy.canCreateInitialProfile(claims)) {
            "Tu cuenta tiene permisos asignados, pero falta su perfil. Contacta al administrador; no se cambiará su rol automáticamente."
        }
        return create()
    }
}

internal class UserProfiles {
    private val db = FirebaseFirestore.getInstance()

    suspend fun ensure(user: FirebaseUser, claims: Map<String, Any?>): Map<String, Any> {
        val reference = db.collection("usuarios").document(user.uid)
        // A concurrent profile creation retries the transaction: never overwrite it with USER/ACTIVO.
        return db.runTransaction { transaction ->
            val current = transaction.get(reference)
            InitialUserProfile.resolve(current.data, claims) {
                val data = InitialUserProfile.data(user.uid, user.email, user.displayName,
                    user.photoUrl?.toString(), FieldValue.serverTimestamp())
                transaction.set(reference, data)
                data
            }
        }.inventoryAwait()
    }
}
