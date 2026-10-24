package com.example.app_clisa.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import com.example.app_clisa.data.ExitStore
import com.example.app_clisa.data.AccessPolicy
import com.example.app_clisa.data.AppModule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.app_clisa.ui.theme.AppclisaTheme
import com.example.app_clisa.ui.components.ClisaLogo
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import java.util.Date

private val Navy = Color(0xFF020D20)
private val Muted = Color(0xFFB0C8E8)
private val Blue = Color(0xFF0055FF)
private val Cyan = Color(0xFF009EFF)
private val CardBorder = Color(0xFF145087)

private enum class Feature(val title: String, val description: String, val accent: Color, val background: Color) {
    SCAN("Registrar y escanear", "Registra salidas de artículos", Color.White, Navy),
    RECORDS("Lista de registros", "Consulta el historial de movimientos", Color.White, Navy),
    PRODUCTS("Gestión de productos", "Administra el catálogo y stock", Color.White, Navy),
    USERS("Gestión de usuarios", "Administra usuarios y permisos", Color.White, Navy),
    REPORTS("Reportes", "Consulta y analiza la información", Color.White, Blue),
    SIGN_OUT("Cerrar sesión", "", Color(0xFFFF174B), Navy),
}

@Composable
fun HomeScreen(onSignOut: () -> Unit, onRegister: () -> Unit = {}, userName: String = "Alexis", authenticated: Boolean = false, onRecords: () -> Unit = {}, onProducts: () -> Unit = {}, onUsers: () -> Unit = {}, onReports: () -> Unit = {}, role: String? = null) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = (context as? Activity)?.let { WindowCompat.getInsetsController(it.window, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            oldStatus?.let { controller.isAppearanceLightStatusBars = it }
            oldNavigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
    val store = remember(context) { ExitStore(context) }
    var selected by remember { mutableStateOf<Feature?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(ZonedDateTime.now(ZoneId.of("America/Lima"))) }
    var liveCount by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(authenticated, now.toLocalDate()) {
        liveCount = null
        val start = now.toLocalDate().atStartOfDay(ZoneId.of("America/Lima"))
        val listener = if (authenticated) FirebaseFirestore.getInstance().collection("registros")
            .whereGreaterThanOrEqualTo("fechaHora", Timestamp(Date.from(start.toInstant())))
            .whereLessThan("fechaHora", Timestamp(Date.from(start.plusDays(1).toInstant())))
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                liveCount = if (error == null && snapshot != null && !snapshot.metadata.isFromCache) snapshot.documents.count {
                    it.getString("tipo") == "SALIDA" && it.getString("estado") == "COMPLETADO"
                } else null
            } else null
        onDispose { listener?.remove() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now(ZoneId.of("America/Lima"))
            delay(30_000)
        }
    }
    BackHandler { confirmExit = true }
    Box(Modifier.fillMaxSize().background(Navy)) {
        DashboardBackground(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 16.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ClisaLogo(width = 196.dp)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(58.dp)) {
                        Surface(onClick = { profile = true }, shape = CircleShape, color = Color(0xFF083B87), border = BorderStroke(1.5.dp, Cyan),
                            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Ver perfil de $userName" }) {
                            Canvas(Modifier.padding(10.dp)) {
                                drawCircle(Color(0xFFE9F3FF), size.width * .19f, Offset(size.width * .5f, size.height * .29f))
                                drawRoundRect(Color(0xFFE9F3FF), Offset(size.width * .16f, size.height * .53f), Size(size.width * .68f, size.height * .36f), CornerRadius(size.width * .18f))
                            }
                        }
                        Box(Modifier.align(Alignment.BottomEnd).size(14.dp).background(Navy, CircleShape).padding(2.dp).background(if (authenticated) Color(0xFF00D58C) else Color(0xFF8297B7), CircleShape))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("Hola, ") }
                    withStyle(SpanStyle(color = Cyan)) { append(userName.substringBefore(' ')) }
                }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Bienvenido al sistema de control\nde inventario de CLISA Technology.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = Color.Transparent, border = BorderStroke(1.dp, CardBorder)) {
                    Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF062B70), Color(0xFF03152F)))).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = Blue, modifier = Modifier.size(54.dp)) {
                            Box(contentAlignment = Alignment.Center) { BellIcon() }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (authenticated) "Todos los registros se guardan" else "Modo demostración", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(if (authenticated) "Cada salida confirmada se guarda para mantener un control actualizado." else "Las salidas de prueba se guardan en este dispositivo.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Feature.entries.filter { feature ->
                    val module = when (feature) {
                        Feature.SCAN -> AppModule.REGISTER
                        Feature.RECORDS -> AppModule.RECORDS
                        Feature.PRODUCTS -> AppModule.PRODUCTS
                        Feature.REPORTS -> AppModule.REPORTS
                        Feature.USERS -> AppModule.USERS
                        Feature.SIGN_OUT -> null
                    }
                    module == null || AccessPolicy.allows(role, module)
                }.forEach { feature ->
                    if (feature == Feature.SIGN_OUT) Spacer(Modifier.height(4.dp))
                    FeatureCard(feature, Modifier.fillMaxWidth()) {
                        when (feature) {
                            Feature.SCAN -> onRegister()
                            Feature.RECORDS -> onRecords()
                            Feature.PRODUCTS -> onProducts()
                            Feature.USERS -> onUsers()
                            Feature.REPORTS -> onReports()
                            Feature.SIGN_OUT -> confirmExit = true
                        }
                    }
                }
            }
        }
    }
    selected?.let { feature ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(feature.title.replace('\n', ' ')) },
            text = { Text("Este módulo será la siguiente parte de la app. El acceso desde el panel ya está preparado.") },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Entendido") } })
    }
    if (profile) AlertDialog(onDismissRequest = { profile = false }, title = { Text(userName) }, text = { Text((if (authenticated) "Tu cuenta y el registro de salidas están conectados a Firebase." else "Estás explorando el panel con un perfil de ejemplo.") + "\n\nRegistros de hoy: ${if (authenticated) liveCount?.toString() ?: "No disponible" else store.countToday(now.toLocalDate()).toString()}") }, confirmButton = { TextButton(onClick = { profile = false }) { Text("Cerrar") } })
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("Cerrar sesión") }, text = { Text("¿Quieres salir del panel y volver a Iniciar sesión?") },
        confirmButton = { TextButton(onClick = { confirmExit = false; onSignOut() }) { Text("Salir") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Cancelar") } })
}

@Composable
private fun FeatureCard(feature: Feature, modifier: Modifier, onClick: () -> Unit) {
    val reports = feature == Feature.REPORTS
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, if (reports) Cyan else CardBorder)) {
        Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(if (reports) listOf(Color(0xFF006BFF), Blue) else listOf(Color(0xFF031A36), Color(0xFF031226))))
            .heightIn(min = if (feature == Feature.SIGN_OUT) 66.dp else 74.dp).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FeatureIcon(feature)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(feature.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (feature.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(feature.description, color = if (reports) Color(0xFFD9EAFF) else Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.width(10.dp))
            Canvas(Modifier.size(16.dp)) {
                val p = Path().apply { moveTo(size.width * .3f, size.height * .15f); lineTo(size.width * .7f, size.height * .5f); lineTo(size.width * .3f, size.height * .85f) }
                drawPath(p, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun FeatureIcon(feature: Feature) {
    Canvas(Modifier.size(36.dp)) {
        val s = size.width / 24f
        val stroke = Stroke(1.7f * s)
        val color = feature.accent
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x*s, y*s), Offset(xx*s, yy*s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, Offset(x*s, y*s), Size(w*s, h*s), CornerRadius(1.5f*s), style = stroke)
        when (feature) {
            Feature.SCAN -> {
                val scanner = Path().apply {
                    moveTo(3*s,8*s); lineTo(3*s,5*s); quadraticTo(3*s,3*s,5*s,3*s); lineTo(8*s,3*s)
                    moveTo(16*s,3*s); lineTo(19*s,3*s); quadraticTo(21*s,3*s,21*s,5*s); lineTo(21*s,8*s)
                    moveTo(3*s,16*s); lineTo(3*s,19*s); quadraticTo(3*s,21*s,5*s,21*s); lineTo(8*s,21*s)
                    moveTo(16*s,21*s); lineTo(19*s,21*s); quadraticTo(21*s,21*s,21*s,19*s); lineTo(21*s,16*s)
                    moveTo(8*s,12*s); lineTo(16*s,12*s)
                }
                for ((width, alpha) in listOf(7f to .04f, 5.5f to .08f, 4f to .18f, 2.8f to .65f)) {
                    drawPath(scanner, Color(0xFF0088FF).copy(alpha = alpha), style = Stroke(width*s, cap = StrokeCap.Round))
                }
                drawPath(scanner, Color(0xFFDDF5FF), style = Stroke(1.6f*s, cap = StrokeCap.Round))
            }
            Feature.RECORDS -> {
                drawPath(Path().apply { moveTo(5*s,2*s); lineTo(15*s,2*s); lineTo(20*s,7*s); lineTo(20*s,22*s); lineTo(5*s,22*s); close() }, color, style = stroke)
                line(15f,2f,15f,7f); line(15f,7f,20f,7f); line(8f,10f,16f,10f); line(8f,14f,16f,14f); line(8f,18f,16f,18f)
            }
            Feature.PRODUCTS -> {
                drawPath(Path().apply { moveTo(12*s, 2*s); lineTo(21*s, 7*s); lineTo(21*s, 17*s); lineTo(12*s, 22*s); lineTo(3*s, 17*s); lineTo(3*s, 7*s); close() }, Color.White, style = stroke)
                line(3f, 7f, 12f, 12f); line(12f, 12f, 21f, 7f); line(12f, 12f, 12f, 22f)
            }
            Feature.REPORTS -> {
                for ((x, top) in listOf(4f to 14f, 11f to 9f, 18f to 3f)) drawRoundRect(Color.White, Offset(x*s,top*s), Size(4*s,(22-top)*s), CornerRadius(2*s))
            }
            Feature.USERS -> {
                drawCircle(Color.White, 3*s, Offset(12*s,6*s), style = stroke)
                drawArc(Color.White,180f,180f,false,Offset(6*s,12*s),Size(12*s,14*s),style=stroke)
                line(6f,19f,18f,19f)
                drawArc(Color.White,90f,180f,false,Offset(2*s,5*s),Size(5*s,5*s),style=stroke)
                drawArc(Color.White,-90f,180f,false,Offset(17*s,5*s),Size(5*s,5*s),style=stroke)
                drawArc(Color.White,180f,90f,false,Offset(1*s,12*s),Size(8*s,12*s),style=stroke)
                drawArc(Color.White,-90f,90f,false,Offset(15*s,12*s),Size(8*s,12*s),style=stroke)
            }
            Feature.SIGN_OUT -> { rect(3f, 3f, 10f, 18f); line(10f, 12f, 22f, 12f); line(18f, 8f, 22f, 12f); line(22f, 12f, 18f, 16f) }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun HomePreview() { AppclisaTheme { HomeScreen(onSignOut = {}) } }

@Composable
private fun BellIcon() {
    Canvas(Modifier.size(30.dp)) {
        val s = size.width / 24f
        val outline = Path().apply {
            moveTo(6*s,16*s); lineTo(6*s,10*s)
            cubicTo(6*s,3*s,18*s,3*s,18*s,10*s)
            lineTo(18*s,16*s); lineTo(20*s,18*s); lineTo(4*s,18*s); close()
        }
        drawPath(outline, Color.White, style = Stroke(1.8f*s, cap = StrokeCap.Round))
        drawLine(Color.White, Offset(12*s,2*s), Offset(12*s,4*s), 1.8f*s, StrokeCap.Round)
        drawArc(Color.White, 0f, 180f, false, Offset(10*s,18*s), Size(4*s,4*s), style = Stroke(1.8f*s))
    }
}

@Composable
private fun DashboardBackground(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF021632), Navy, Color(0xFF010813))))
        val sweep = Path().apply {
            moveTo(w*.34f,0f); lineTo(w*.78f,0f)
            cubicTo(w*.60f,h*.09f,w*.65f,h*.17f,w*.25f,h*.13f)
            lineTo(0f,h*.09f); lineTo(0f,0f); close()
        }
        drawPath(sweep, Brush.linearGradient(listOf(Color(0xFF042959), Color(0xFF020D20)), Offset(w,0f), Offset(0f,h*.18f)))
        val edge = Path().apply {
            moveTo(w*.78f,h*.03f)
            cubicTo(w*.63f,h*.10f,w*.64f,h*.17f,w*.32f,h*.135f)
        }
        drawPath(edge, Color(0xFF006AFF).copy(alpha = .45f), style = Stroke(.6.dp.toPx()))
        fun triangle(points: List<Offset>, color: Color) {
            drawPath(Path().apply { moveTo(points[0].x,points[0].y); points.drop(1).forEach { lineTo(it.x,it.y) }; close() },color)
        }
        triangle(listOf(Offset(w,h*.16f),Offset(w*.55f,h*.24f),Offset(w,h*.27f)),Color(0xFF063A85).copy(alpha=.5f))
        triangle(listOf(Offset(w,h*.12f),Offset(w*.66f,h*.19f),Offset(w,h*.22f)),Color(0xFF063373).copy(alpha=.4f))
        triangle(listOf(Offset(w,h*.20f),Offset(w*.36f,h*.30f),Offset(w*.78f,h*.22f)),Color(0xFF022251).copy(alpha=.55f))
    }
}
