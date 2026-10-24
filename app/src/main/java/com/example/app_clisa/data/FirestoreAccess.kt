package com.example.app_clisa.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

/** Defense in depth for repository entry points; published rules remain the security boundary. */
internal object FirestoreAccess {
    suspend fun requireModule(module: AppModule): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Vuelve a iniciar sesión.")
        val claims = user.getIdToken(false).inventoryAwait().claims
        val profile = FirebaseFirestore.getInstance().collection("usuarios").document(user.uid)
            .get(Source.SERVER).inventoryAwait()
        check(FirebaseAuth.getInstance().currentUser?.uid == user.uid) { "La sesión cambió. Vuelve a iniciar sesión." }
        AccessPolicy.requireAccess(claims, profile.getString("role"), profile.getString("estado"), module)
        return user.uid
    }
}
