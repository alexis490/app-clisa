package com.example.app_clisa.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import java.time.Instant

data class DirectoryUser(val id: String, val name: String, val email: String, val role: String?,
    val state: String?, val store: String?, val createdAt: Instant?)
data class DirectoryAccess(val uid: String, val canManage: Boolean)
data class UserPage(val users: List<DirectoryUser>, val cursor: DocumentSnapshot?, val hasMore: Boolean)
data class UserCounts(val all: Long, val active: Long, val blocked: Long)
enum class UserFilter(val state: String?) { ALL(null), ACTIVE("ACTIVO"), BLOCKED("BLOQUEADO") }

/** Auth remains the authority for roles. No creation or custom-claim edits from Android. */
class UserDirectory {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    suspend fun access(): DirectoryAccess {
        FirestoreAccess.requireModule(AppModule.USERS)
        val user = auth.currentUser ?: error("Inicia sesión para consultar usuarios.")
        val claims = user.getIdToken(false).inventoryAwait().claims
        return DirectoryAccess(user.uid, UserManagementPolicy.canManage(claims))
    }
    private fun query(filter: UserFilter): Query = db.collection("usuarios").let {
        if (filter.state == null) it else it.whereEqualTo("estado", filter.state)
    }
    suspend fun page(access: DirectoryAccess, filter: UserFilter, cursor: DocumentSnapshot? = null): UserPage {
        check(FirestoreAccess.requireModule(AppModule.USERS) == access.uid && access.canManage) { "No tienes permiso para administrar usuarios." }
        if (!access.canManage) {
            val document = db.collection("usuarios").document(access.uid).get(Source.SERVER).inventoryAwait()
            val user = if (document.exists()) document.user() else null
            return UserPage(listOfNotNull(user).filter { filter.state == null || it.state == filter.state }, null, false)
        }
        var request = query(filter).limit(20)
        if (cursor != null) request = request.startAfter(cursor)
        val documents = request.get(Source.SERVER).inventoryAwait().documents
        return UserPage(documents.map { it.user() }, documents.lastOrNull(), documents.size == 20)
    }
    suspend fun counts(access: DirectoryAccess): UserCounts {
        check(FirestoreAccess.requireModule(AppModule.USERS) == access.uid && access.canManage) { "No tienes permiso para administrar usuarios." }
        if (!access.canManage) {
            val users = page(access, UserFilter.ALL).users
            return UserCounts(users.size.toLong(), users.count { it.state == "ACTIVO" }.toLong(), users.count { it.state == "BLOQUEADO" }.toLong())
        }
        suspend fun count(filter: UserFilter) = query(filter).count().get(AggregateSource.SERVER).inventoryAwait().count
        return UserCounts(count(UserFilter.ALL), count(UserFilter.ACTIVE), count(UserFilter.BLOCKED))
    }
    suspend fun setState(userId: String, next: String): DirectoryUser {
        require(next in listOf("ACTIVO", "BLOQUEADO"))
        val actor = auth.currentUser ?: error("Vuelve a iniciar sesión.")
        val claims = actor.getIdToken(true).inventoryAwait().claims
        UserManagementPolicy.validateStateChange(DirectoryAccess(actor.uid, UserManagementPolicy.canManage(claims)), userId, next)
        val reference = db.collection("usuarios").document(userId)
        return db.runTransaction { transaction ->
            val profile = transaction.get(db.collection("usuarios").document(actor.uid))
            AccessPolicy.requireAccess(claims, profile.getString("role"), profile.getString("estado"), AppModule.USERS)
            val target = transaction.get(reference)
            check(target.exists()) { "La cuenta ya no está disponible." }
            UserManagementPolicy.validateTargetRole(target.getString("role"))
            if (target.getString("estado") != next)
                transaction.update(reference, mapOf("estado" to next, "actualizadoEn" to FieldValue.serverTimestamp()))
            target.user().copy(state = next)
        }.inventoryAwait()
    }
    private fun DocumentSnapshot.user() = DirectoryUser(id, text("displayName") ?: text("username") ?: "Sin nombre",
        text("email") ?: "Sin correo", text("role"), text("estado"), text("storeName"),
        getTimestamp("creadoEn")?.toDate()?.toInstant() ?: getTimestamp("createdAt")?.toDate()?.toInstant())
    private fun DocumentSnapshot.text(field: String) = getString(field)?.trim()?.takeIf { it.isNotEmpty() }
}
