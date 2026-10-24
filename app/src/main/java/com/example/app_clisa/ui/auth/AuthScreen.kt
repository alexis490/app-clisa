package com.example.app_clisa.ui.auth

import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app_clisa.ui.theme.AppclisaTheme
import com.example.app_clisa.R
import com.example.app_clisa.ui.components.ClisaLogo

private val Blue = Color(0xFF0055FF)
private val LinkBlue = Color(0xFF00A7FF)
private val Navy = Color(0xFF020722)
private val Muted = Color(0xFFA8BAD9)
private val Border = Color(0xFF30466D)

@Composable
fun AuthScreen(
    onDemoAccess: () -> Unit = {},
    busy: Boolean = false,
    authMessage: String? = null,
    onClearMessage: () -> Unit = {},
    onLogin: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onRegister: (String, String, String) -> Unit = { _, _, _ -> },
    onGoogle: (android.content.Context, Boolean) -> Unit = { _, _ -> },
    onRecover: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
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
    var register by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    // Secrets remain in memory, outside saved instance state.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var rememberMe by rememberSaveable { mutableStateOf(true) }
    var terms by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun navigate(toRegister: Boolean) {
        if (busy) return
        focusManager.clearFocus()
        register = toRegister
        password = ""
        confirmation = ""
        submitted = false
    }
    BackHandler(register || busy) { if (!busy) navigate(false) }
    val emailValid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val passwordValid = if (register) password.length >= 8 else password.isNotBlank()
    BoxWithConstraints(Modifier.fillMaxSize().background(Navy)) {
        val screenHeight = maxHeight
        AuthBackdrop(register, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (register) {
                IconButton(onClick = { navigate(false) }, enabled = !busy, modifier = Modifier.padding(start = 14.dp, top = 32.dp)) {
                    FormIcon("back", "Volver a iniciar sesión", Muted)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp).padding(top = 20.dp, bottom = 20.dp)) {
                    Text("Crear cuenta", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Completa tus datos para registrarte", color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(Modifier.fillMaxWidth().height((screenHeight * .37f).coerceIn(235.dp, 340.dp)).padding(top = 38.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ClisaLogo(width = 270.dp)
                    Spacer(Modifier.height(16.dp))
                    Text("Control rápido de movimientos", color = Color(0xFFCDD9F1), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(22.dp),
                color = Color.Transparent, border = if (register) null else BorderStroke(1.dp, Border.copy(alpha = .45f))) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xF2111B38), Color(0xEF040C23)))).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 14.dp)) {
                    if (!register) {
                        Text("Iniciar sesión", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(6.dp))
                        Text("Ingresa tus credenciales para continuar", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(18.dp))
                    }
                    if (register) {
                        AuthField(name, { name = it }, "Nombre completo", "user", error = submitted && name.isBlank(), errorText = "Ingresa tu nombre completo")
                        Spacer(Modifier.height(12.dp))
                    }
                    AuthField(email, { email = it }, "Correo electrónico", "email", keyboardType = KeyboardType.Email, error = submitted && !emailValid, errorText = "Ingresa un correo válido")
                    Spacer(Modifier.height(12.dp))
                    AuthField(password, { password = it }, "Contraseña", "lock", secret = true, last = !register, error = submitted && !passwordValid, errorText = if (register) "Usa al menos 8 caracteres" else "Ingresa tu contraseña")
                    if (register) {
                        Spacer(Modifier.height(12.dp))
                        AuthField(confirmation, { confirmation = it }, "Confirmar contraseña", "lock", secret = true, last = true, error = submitted && confirmation != password, errorText = "Las contraseñas no coinciden")
                    }
                    Spacer(Modifier.height(12.dp))
                    if (register) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(terms, { terms = it }, enabled = !busy, modifier = Modifier.size(32.dp), colors = CheckboxDefaults.colors(checkedColor = Blue, uncheckedColor = Muted, checkmarkColor = Color.White))
                            Text("Acepto los", color = Color(0xFFDCE5F6), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { notice = "Términos y condiciones" to "Los términos de CLISA aún no se han configurado. Esta pantalla es una vista previa del registro." }, contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.textButtonColors(contentColor = LinkBlue)) { Text("Términos y Condiciones", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (submitted && !terms) Text("Debes aceptar los términos para continuar", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(rememberMe, { rememberMe = it }, enabled = !busy, modifier = Modifier.size(32.dp), colors = CheckboxDefaults.colors(checkedColor = Blue, uncheckedColor = Muted, checkmarkColor = Color.White))
                            Text("Recordarme", color = Color(0xFFDCE5F6), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                if (emailValid) onRecover(email) else notice = "Recuperar contraseña" to "Escribe primero un correo válido en el campo Correo electrónico."
                            }, enabled = !busy, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = LinkBlue)) { Text("¿Olvidaste tu contraseña?", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        submitted = true
                        if (emailValid && passwordValid && (!register || (name.isNotBlank() && confirmation == password && terms))) {
                            if (register) onRegister(name, email, password) else onLogin(email, password, rememberMe)
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White, disabledContainerColor = Blue.copy(alpha = .5f))) {
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text(if (register) "Registrarse" else "Iniciar sesión", style = MaterialTheme.typography.labelLarge)
                    }
                    if (!register) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f), color = Color(0xFF687D9F))
                            Text("o", Modifier.padding(horizontal = 16.dp), color = Color.White, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(Modifier.weight(1f), color = Color(0xFF687D9F))
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { onGoogle(context, rememberMe) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp), border = null, colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFF4F5FA), contentColor = Navy)) {
                            Image(painter = painterResource(R.drawable.google_logo), contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Continuar con Google", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (register) "¿Ya tienes una cuenta?" else "¿No tienes una cuenta?", color = Muted, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { navigate(!register) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 5.dp), colors = ButtonDefaults.textButtonColors(contentColor = LinkBlue)) { Text(if (register) "Iniciar sesión" else "Regístrate", style = MaterialTheme.typography.labelMedium) }
                    }
                    if (!register) {
                        TextButton(onClick = onDemoAccess, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally), colors = ButtonDefaults.textButtonColors(contentColor = Muted)) {
                            Text("Explorar demostración", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    notice?.let { (title, message) ->
        AlertDialog(onDismissRequest = { notice = null }, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("Entendido") } })
    }
    authMessage?.let { message ->
        AlertDialog(onDismissRequest = onClearMessage, title = { Text("Acceso a CLISA") }, text = { Text(message) }, confirmButton = { TextButton(onClick = onClearMessage) { Text("Entendido") } })
    }
}

@Composable
private fun AuthField(value: String, onChange: (String) -> Unit, label: String, icon: String, secret: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, error: Boolean = false, errorText: String = "", last: Boolean = false) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), placeholder = { Text(label, style = MaterialTheme.typography.bodyMedium) }, textStyle = MaterialTheme.typography.bodyMedium, singleLine = true,
        leadingIcon = { FormIcon(icon) },
        trailingIcon = if (secret) ({ IconButton(onClick = { visible = !visible }) { FormIcon(if (visible) "hide" else "eye", if (visible) "Ocultar contraseña" else "Mostrar contraseña") } }) else null,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        shape = RoundedCornerShape(14.dp), isError = error, supportingText = if (error) ({ Text(errorText) }) else null,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LinkBlue, unfocusedBorderColor = Border,
            focusedContainerColor = Color(0xFF142240), unfocusedContainerColor = Color(0xFF14213D),
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = LinkBlue,
            focusedPlaceholderColor = Muted, unfocusedPlaceholderColor = Muted,
            errorTextColor = Color.White, errorContainerColor = Color(0xFF14213D), errorSupportingTextColor = Color(0xFFFFA9B4)))
}

@Composable
private fun AuthBackdrop(register: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Navy)) {
        val imageHeight = if (register) (maxHeight * .32f).coerceAtLeast(200.dp) else (maxHeight * .24f).coerceAtLeast(180.dp)
        Image(painterResource(R.drawable.auth_equipment), contentDescription = null, contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxWidth().height(imageHeight).then(
                if (register) Modifier.align(Alignment.BottomCenter) else Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .14f)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            if (register) listOf(Navy.copy(alpha = .05f), Navy.copy(alpha = .05f), Color.Transparent)
            else listOf(Navy.copy(alpha = .05f), Color.Transparent, Navy.copy(alpha = .8f), Navy))))
    }
}

@Composable
internal fun GeometricBackground(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF05162F), Color(0xFF103D7A)), end = Offset(size.width, size.height * .5f)))
        fun polygon(color: Color, points: List<Offset>) {
            drawPath(Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }, color)
        }
        val w = size.width
        polygon(Color(0xFF123B78), listOf(Offset(w * .4f, 0f), Offset(w * .94f, 0f), Offset(w * .64f, w * .25f)))
        polygon(Color(0xFF0A4CAB), listOf(Offset(w * .64f, w * .25f), Offset(w * .94f, 0f), Offset(w, w * .43f)))
        polygon(Color(0xFF0A2857), listOf(Offset(0f, w * .12f), Offset(w, w * .95f), Offset(0f, w * .95f)))
    }
}

@Composable
private fun FormIcon(kind: String, description: String? = null, color: Color = Muted) {
    Canvas(Modifier.size(22.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val s = size.width / 24f
        val stroke = Stroke(1.7f * s)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x*s, y*s), Offset(xx*s, yy*s), strokeWidth = stroke.width)
        when (kind) {
            "email" -> {
                drawRoundRect(color, Offset(2*s, 5*s), Size(20*s, 14*s), CornerRadius(1.5f*s), style = stroke)
                line(2f, 6f, 12f, 13f); line(12f, 13f, 22f, 6f)
            }
            "lock" -> {
                drawRoundRect(color, Offset(5*s, 10*s), Size(14*s, 11*s), CornerRadius(2*s), style = stroke)
                drawArc(color, 180f, 180f, false, Offset(8*s, 2*s), Size(8*s, 14*s), style = stroke)
                line(12f, 14f, 12f, 17f)
            }
            "user" -> {
                drawCircle(color, 4*s, Offset(12*s, 6*s), style = stroke)
                drawArc(color, 180f, 180f, false, Offset(4*s, 13*s), Size(16*s, 16*s), style = stroke)
                line(4f, 21f, 20f, 21f)
            }
            "back" -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            else -> {
                drawPath(Path().apply {
                    moveTo(2*s, 12*s)
                    cubicTo(7*s, 3*s, 17*s, 3*s, 22*s, 12*s)
                    cubicTo(17*s, 21*s, 7*s, 21*s, 2*s, 12*s)
                    close()
                }, color, style = stroke)
                drawCircle(color, 3*s, Offset(12*s, 12*s), style = stroke)
                if (kind == "hide") line(3f, 3f, 21f, 21f)
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LoginPreview() { AppclisaTheme { AuthScreen() } }

