package com.example.app_clisa.ui.users

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.app_clisa.data.*
import com.example.app_clisa.ui.registration.RegisterIcon
import com.example.app_clisa.ui.registration.RegistrationBanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val White = Color(0xFFF1F5FF)
private val Muted = Color(0xFFABC7EC)
private val Blue = Color(0xFF0061FF)
private val Border = Color(0xFF07568B)
private val Green = Color(0xFF00DF98)
private val Red = Color(0xFFFF1744)
private val Background = Color(0xFF020E21)

@Suppress("DEPRECATION")
@Composable
fun UsersScreen(onBack: () -> Unit, authenticated: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val controller = (context as? Activity)?.let { WindowCompat.getInsetsController(it.window, view) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            status?.let { controller.isAppearanceLightStatusBars = it }
            navigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
    val repository = remember { UserDirectory() }
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf<DirectoryAccess?>(null) }
    var page by remember { mutableStateOf<UserPage?>(null) }
    var counts by remember { mutableStateOf<UserCounts?>(null) }
    var selected by remember { mutableStateOf<DirectoryUser?>(null) }
    var filter by remember { mutableStateOf(UserFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingState by remember { mutableStateOf<String?>(null) }
    fun leave() { if (!saving) { if (selected != null) selected = null else onBack() } }
    BackHandler { leave() }
    LaunchedEffect(authenticated, filter, reload) {
        if (!authenticated) return@LaunchedEffect
        loading = true; error = null; page = null
        try {
            val current = repository.access()
            access = current
            page = repository.page(current, filter)
            if (counts == null) counts = repository.counts(current)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = inventoryError(e) }
        finally { loading = false }
    }
    LaunchedEffect(ProductSearch.normalize(query)) { delay(350); appliedQuery = ProductSearch.normalize(query) }
    fun more() {
        val current = access ?: return
        val old = page ?: return
        if (loading || !old.hasMore) return
        val chosenFilter = filter
        loading = true
        scope.launch {
            try {
                val next = repository.page(current, chosenFilter, old.cursor)
                if (filter == chosenFilter) page = next.copy(users = (old.users + next.users).distinctBy { it.id })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = inventoryError(e) }
            finally { loading = false }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, onPrimary = White, surface = Color(0xFF031A34), onSurface = White,
        onSurfaceVariant = Muted, outline = Border)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Box(Modifier.fillMaxWidth()) {
                    RegistrationBanner(Modifier.matchParentSize(), showScanner = false)
                    Column {
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { leave() }, enabled = !saving) { RegisterIcon("back", White, "Volver") }
                            Spacer(Modifier.weight(1f))
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = !loading && !saving && authenticated) { UserSymbol("menu", White, "Opciones") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Actualizar") }, onClick = { menu = false; selected = null; counts = null; reload++ })
                                }
                            }
                        }
                        Text(if (selected == null) "Gestión de usuarios" else "Detalle de usuario", color = White, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                        Text(if (selected == null) "Administra los usuarios y sus permisos" else "Administra los permisos y estado de la cuenta", color = Muted,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 16.dp))
                    }
                }
                if (!authenticated) Text("Inicia sesión para consultar usuarios reales.", color = Muted, modifier = Modifier.padding(20.dp))
                else if (selected == null) {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Buscar por nombre o correo…", style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { RegisterIcon("search", White, null) }, shape = RoundedCornerShape(12.dp), singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), textStyle = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        UserFilterChip("Todos", counts?.all, filter == UserFilter.ALL, Modifier.weight(1f)) { filter = UserFilter.ALL }
                        UserFilterChip("Activos", counts?.active, filter == UserFilter.ACTIVE, Modifier.weight(1f)) { filter = UserFilter.ACTIVE }
                        UserFilterChip("Bloqueados", counts?.blocked, filter == UserFilter.BLOCKED, Modifier.weight(1.25f)) { filter = UserFilter.BLOCKED }
                    }
                    if (access?.canManage == false) Text("Solo puedes consultar tu cuenta. La gestión de otras cuentas requiere Super Admin.", color = Muted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    val users = page?.users.orEmpty().filter { appliedQuery.isEmpty() || ProductSearch.matches(it.name, it.email, appliedQuery) }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(users, key = { it.id }) { item -> UserRow(item) { selected = item } }
                        item {
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            error?.let { Text(it, color = Red, modifier = Modifier.padding(10.dp)); TextButton(onClick = { reload++ }, enabled = !loading) { Text("Reintentar") } }
                            if (!loading && error == null && users.isEmpty()) Text("No hay usuarios que coincidan en las cuentas cargadas.", color = Muted, modifier = Modifier.padding(12.dp))
                            if (page?.hasMore == true) TextButton(onClick = { more() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Cargar más usuarios") }
                            if (appliedQuery.isNotEmpty() && page?.hasMore == true) Text("La búsqueda incluye las páginas cargadas. Carga más para revisar las siguientes.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    val user = selected!!
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        UserCard {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(user, 56)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(user.name, color = White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(user.email, color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                StateBadge(user.state)
                            }
                            InfoLine("store", "Tienda:", user.store ?: "No registrada")
                            val date = user.createdAt?.atZone(ZoneId.of("America/Lima"))?.format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-PE"))) ?: "No registrada"
                            InfoLine("calendar", "Fecha de registro:", date)
                            Spacer(Modifier.height(10.dp))
                        }
                        UserCard {
                            CardTitle("shield", "Rol y permisos")
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Rol actual", color = Muted, modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = { notice = "Esta función aún no está disponible." },
                                    enabled = access?.canManage == true && access?.uid != user.id && user.role in listOf("USER", "ADMIN") && !saving,
                                    shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Border)) {
                                    Text(roleName(user.role), color = White); Spacer(Modifier.width(24.dp)); RegisterIcon("down", White, null, 18.dp)
                                }
                            }
                            Surface(Modifier.padding(12.dp), shape = RoundedCornerShape(10.dp), color = Color(0xFF032858), border = BorderStroke(1.dp, Blue)) {
                                Text("El rol define las funciones a las que puede acceder el usuario. Solo un administrador autorizado puede modificarlo.",
                                    color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                            }
                        }
                        UserCard {
                            CardTitle("lock", "Estado de la cuenta")
                            val active = user.state == "ACTIVO"
                            Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp), shape = RoundedCornerShape(10.dp), color = if (active) Color(0xFF003A35) else Color(0xFF3C1226), border = BorderStroke(1.dp, if (active) Green else Red)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    UserSymbol(if (active) "check" else "lock", if (active) Green else Red, null)
                                    Column(Modifier.padding(start = 12.dp)) {
                                        Text(if (active) "Cuenta activa" else if (user.state == "BLOQUEADO") "Cuenta bloqueada" else "Estado no registrado", color = if (active) Green else Red)
                                        Text(if (active) "Acceso sujeto a su rol y permisos." else "Las reglas restringen el acceso a los datos.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pendingState = "BLOQUEADO" }, enabled = access?.canManage == true && access?.uid != user.id && user.role in listOf("USER", "ADMIN") && active && !saving,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Red), contentPadding = PaddingValues(8.dp)) {
                                    UserSymbol("lock", Red, null); Spacer(Modifier.width(6.dp)); Text("Bloquear cuenta", color = Red, fontSize = 12.sp)
                                }
                                OutlinedButton(onClick = { pendingState = "ACTIVO" }, enabled = access?.canManage == true && access?.uid != user.id && user.role in listOf("USER", "ADMIN") && !active && !saving,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Blue), contentPadding = PaddingValues(8.dp)) {
                                    UserSymbol("play", Color(0xFF009EFF), null); Spacer(Modifier.width(6.dp)); Text("Activar cuenta", color = Color(0xFF009EFF), fontSize = 12.sp)
                                }
                            }
                            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        pendingState?.let { next ->
            val user = selected
            if (user != null) AlertDialog(onDismissRequest = { pendingState = null }, title = { Text(if (next == "ACTIVO") "Activar cuenta" else "Bloquear cuenta") },
                text = { Text("¿Confirmas ${if (next == "ACTIVO") "activar" else "bloquear"} el acceso de ${user.name} a los datos de CLISA?") },
                confirmButton = { TextButton(onClick = {
                    pendingState = null; saving = true
                    scope.launch {
                        try {
                            val updated = repository.setState(user.id, next)
                            selected = updated
                            page = page?.copy(users = page!!.users.map { if (it.id == updated.id) updated else it }.filter { filter.state == null || it.state == filter.state })
                            counts = null
                            try { access?.let { counts = repository.counts(it) } }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { notice = "El estado se actualizó. No se pudieron actualizar los contadores: ${inventoryError(e)}" }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { notice = inventoryError(e) }
                        finally { saving = false }
                    }
                }) { Text("Confirmar") } }, dismissButton = { TextButton(onClick = { pendingState = null }) { Text("Cancelar") } })
        }
        notice?.let { AlertDialog(onDismissRequest = { notice = null }, title = { Text("Gestión de usuarios") }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("Entendido") } }) }
    }
}

private fun roleName(role: String?) = when (role) { "SUPER_ADMIN" -> "Super Admin"; "ADMIN" -> "Admin"; "USER" -> "Usuario"; else -> "Sin rol" }
@Composable
private fun UserCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.Transparent, border = BorderStroke(1.dp, Border)) {
        Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF031C34), Color(0xFF02152B)))), content = content)
    }
}
@Composable
private fun UserRow(user: DirectoryUser, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Color(0xFF031A34), border = BorderStroke(1.dp, Border)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(user, 46)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(user.name, color = White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(user.email, color = Muted, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    RegisterIcon("store", Muted, null, 16.dp)
                    Text(user.store ?: "Tienda no registrada", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val roleColor = when (user.role) { "SUPER_ADMIN" -> Color(0xFF6516B5); "ADMIN" -> Blue; else -> Color(0xFF2C4C77) }
                Surface(shape = RoundedCornerShape(9.dp), color = roleColor) {
                    Text(roleName(user.role), color = White, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                StateBadge(user.state)
            }
            RegisterIcon("right", White, null, 18.dp, Modifier.padding(start = 8.dp))
        }
    }
}
@Composable
private fun Avatar(user: DirectoryUser, size: Int) {
    Surface(Modifier.size(size.dp), shape = CircleShape, color = if (user.state == "BLOQUEADO") Color(0xFFC57C92) else Color(0xFF568FF6)) {
        Box(contentAlignment = Alignment.Center) { Text(user.name.firstOrNull()?.uppercase() ?: "?", color = White, fontSize = (size / 2).sp, fontWeight = FontWeight.Medium) }
    }
}
@Composable
private fun StateBadge(state: String?) {
    Surface(shape = RoundedCornerShape(12.dp), color = when (state) { "ACTIVO" -> Color(0xFF008C6D); "BLOQUEADO" -> Red; else -> Color(0xFF344960) }) {
        Text(when (state) { "ACTIVO" -> "Activo"; "BLOQUEADO" -> "Bloqueado"; else -> "Sin estado" }, color = White, fontSize = 11.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
@Composable
private fun CardTitle(icon: String, title: String) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        UserSymbol(icon, White, null); Text(title, color = White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
    }
}
@Composable
private fun InfoLine(icon: String, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        RegisterIcon(icon, Muted, null, 20.dp)
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        Text(value, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
    }
}
@Composable
private fun UserFilterChip(label: String, count: Long?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp), color = if (selected) Blue else Color(0xFF02182E), border = BorderStroke(1.dp, Border)) {
        Box(Modifier.heightIn(min = 38.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text("$label (${count?.toString() ?: "—"})", color = White, fontSize = 11.sp)
        }
    }
}
