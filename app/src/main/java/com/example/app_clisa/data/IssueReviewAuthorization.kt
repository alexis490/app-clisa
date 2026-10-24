package com.example.app_clisa.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

internal data class IssueReviewer(val uid: String, val role: String)
internal object IssueReviewAuthorization {
    /** One own-profile read on explicit save, never on typing/filtering. No inventory writes. */
    suspend fun verify(): IssueReviewer {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Inicia sesión para guardar la revisión.")
        val claims = user.getIdToken(true).inventoryAwait().claims
        val profile = FirebaseFirestore.getInstance().collection("usuarios").document(user.uid).get(Source.SERVER).inventoryAwait()
        check(FirebaseAuth.getInstance().currentUser?.uid == user.uid) { "La sesión cambió." }
        return IssueReviewer(user.uid, AccessPolicy.requireAccess(claims, profile.getString("role"), profile.getString("estado"), AppModule.PRODUCTS))
    }
}
