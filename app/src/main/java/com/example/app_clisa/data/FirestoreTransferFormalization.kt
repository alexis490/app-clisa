package com.example.app_clisa.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.Timestamp

enum class FormalizationOutcome { CONFIRMED, ALREADY_COMPLETED }

/** Only a partial update of an existing record; no stock references, creates or write batches. */
internal class FirestoreTransferFormalization(private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {
    suspend fun confirm(recordId: String): FormalizationOutcome {
        require(recordId.isNotBlank() && '/' !in recordId)
        val user = auth.currentUser ?: error("Vuelve a iniciar sesión.")
        val claims = user.getIdToken(false).inventoryAwait().claims
        check(auth.currentUser?.uid == user.uid) { "La sesión cambió. Vuelve a iniciar sesión." }
        return try { db.runTransaction { tx ->
            check(auth.currentUser?.uid == user.uid) { "La sesión cambió. Vuelve a iniciar sesión." }
            val profile = tx.get(db.collection("usuarios").document(user.uid))
            val role = AccessPolicy.requireAccess(claims, profile.getString("role"), profile.getString("estado"), AppModule.RECORDS)
            check(TransferFormalization.authorized(role)) { "Tu cuenta no tiene permiso para confirmar traspasos." }
            val target = db.collection("registros").document(recordId)
            val record = tx.get(target)
            check(record.exists()) { "El registro ya no está disponible." }
            val fields = record.data.orEmpty()
            check(TransferFormalization.isTransfer(fields)) { "El registro no es un traspaso confirmable de CLISA hacia TOQEN." }
            if (TransferFormalization.state(fields) == FormalizationState.COMPLETED) FormalizationOutcome.ALREADY_COMPLETED
            else {
                TransferFormalization.requireConfirmation(role, fields)
                tx.update(target, mapOf("estadoFormalizacion" to "COMPLETADO",
                    "formalizadoEn" to FieldValue.serverTimestamp(), "formalizadoPor" to user.uid))
                FormalizationOutcome.CONFIRMED
            }
        }.inventoryAwait() } catch (failure: FirebaseFirestoreException) {
            // Rules can reject the losing attempt before the SDK retries its stale read.
            // Resolve using authorized server reads, never a second blind write or another identity.
            if (failure.code != FirebaseFirestoreException.Code.PERMISSION_DENIED && failure.code != FirebaseFirestoreException.Code.ABORTED) throw failure
            check(auth.currentUser?.uid == user.uid) { "La sesión cambió. Vuelve a iniciar sesión." }
            val profile = db.collection("usuarios").document(user.uid).get(Source.SERVER).inventoryAwait()
            val role = AccessPolicy.requireAccess(claims, profile.getString("role"), profile.getString("estado"), AppModule.RECORDS)
            check(TransferFormalization.authorized(role)) { "Tu cuenta no tiene permiso para confirmar traspasos." }
            val committed = db.collection("registros").document(recordId).get(Source.SERVER).inventoryAwait()
            check(auth.currentUser?.uid == user.uid) { "La sesión cambió. Vuelve a iniciar sesión." }
            val fields = committed.data.orEmpty()
            if (TransferFormalization.isTransfer(fields) && fields["estadoFormalizacion"] == "COMPLETADO" &&
                fields["formalizadoEn"] is Timestamp && !(fields["formalizadoPor"] as? String).isNullOrBlank()) FormalizationOutcome.ALREADY_COMPLETED
            else throw failure
        }
    }
}
