package com.example.app_clisa.ui.products

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.app_clisa.data.excel.ComparisonProgress
import com.example.app_clisa.data.excel.ExcelInventory
import com.example.app_clisa.ui.registration.RegisterIcon
import com.example.app_clisa.ui.registration.RegistrationBanner

private val FlowWhite = Color(0xFFF1F5FF)
private val FlowMuted = Color(0xFFA9C8EC)
private val FlowBorder = Color(0xFF07568B)
private val FlowGreen = Color(0xFF00E6A0)
private val FlowBlue = Color(0xFF0061FF)
private val FlowAmber = Color(0xFFFFBC39)

/** Visual host only. It cannot read files, query Firestore or change inventory. */
@Composable
internal fun ImportFlowFrame(title: String, subtitle: String, step: Int, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MaterialTheme(colorScheme = darkColorScheme(primary = FlowBlue, secondary = FlowGreen,
            background = Color(0xFF000B1A), surface = Color(0xFF03152C), onSurface = FlowWhite, onBackground = FlowWhite)) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF000B1A)) {
                Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF00132F), Color(0xFF000915))))
                    .statusBarsPadding().navigationBarsPadding().imePadding()) {
                    Box(Modifier.fillMaxWidth()) {
                        RegistrationBanner(Modifier.matchParentSize(), showScanner = false)
                        Column(Modifier.padding(bottom = 12.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onBack, modifier = Modifier.testTag("import-back")) { RegisterIcon("back", FlowWhite, "Volver") }
                                Text(title, modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(subtitle, color = FlowMuted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 48.dp, end = 14.dp))
                        }
                    }
                    ImportStageIndicator(step, Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                    content()
                }
            }
        }
    }
}

@Composable
internal fun ImportStageIndicator(step: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            drawLine(FlowBorder, Offset(size.width * .1f, size.height / 2), Offset(size.width * .9f, size.height / 2), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Seleccionar", "Analizar", "Revisar", "Sincronizar", "Resultados").forEachIndexed { index, label ->
                val current = index + 1 == step
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = CircleShape, color = if (current) FlowGreen else Color(0xFF00152D),
                        border = BorderStroke(if (current) 2.dp else 1.dp, if (current) FlowGreen else FlowBorder)) {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Text("${index + 1}",
                            color = if (current) Color(0xFF00152D) else FlowMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                    }
                    Text(label, color = if (current) FlowWhite else FlowMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
internal fun ImportSelectionScreen(fileName: String, fileBytes: Long?, report: ExcelInventory?, error: String?,
    comparisonReady: Boolean, onBack: () -> Unit, onPick: () -> Unit, onClear: () -> Unit, onAnalyze: () -> Unit, safetyContent: @Composable () -> Unit = {},
    connectionContent: (@Composable () -> Unit)? = null) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    ImportFlowFrame("Importar inventario", "Revisa tus productos y existencias\ndesde Excel", 1, onBack) {
        LazyColumn(Modifier.weight(1f).testTag("import-selection"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FlowCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        ImportExcelSymbol(Modifier.size(64.dp))
                        Text("Selecciona un archivo Excel", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Formato soportado: .xlsx\nDebe contener códigos y cantidades de los productos.", color = FlowMuted,
                            fontSize = 11.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().testTag("import-pick"), shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FlowBlue)) {
                            Canvas(Modifier.size(18.dp)) {
                                val s = size.width / 24f
                                drawPath(Path().apply { moveTo(5*s, 2*s); lineTo(15*s, 2*s); lineTo(20*s, 7*s)
                                    lineTo(20*s, 22*s); lineTo(5*s, 22*s); close(); moveTo(15*s, 2*s); lineTo(15*s, 7*s); lineTo(20*s, 7*s) },
                                    FlowWhite, style = Stroke(1.6f*s))
                                drawLine(FlowWhite, Offset(8*s, 12*s), Offset(17*s, 12*s), strokeWidth = 1.6f*s)
                                drawLine(FlowWhite, Offset(8*s, 16*s), Offset(17*s, 16*s), strokeWidth = 1.6f*s)
                            }
                            Text(if (fileName.isEmpty()) "Seleccionar archivo" else "Seleccionar otro archivo", color = FlowWhite,
                                modifier = Modifier.padding(start = 8.dp), fontSize = 12.sp)
                        }
                    }
                }
            }
            if (fileName.isNotEmpty()) item { SelectedExcelCard(fileName, fileBytes, onClear) }
            connectionContent?.let { connection -> item { connection() } }
            if (report != null) item { safetyContent() }
            item {
                FlowCard {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Columnas detectadas", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(if (report != null) "✓ Reconocidas" else "Pendiente", color = if (report != null) FlowGreen else FlowMuted, fontSize = 10.sp)
                        }
                        listOf("Lote / Nº de serie", "Referencia Interna", "Producto", "Cantidad").forEach { column ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (report != null) "✓" else "○", color = if (report != null) FlowGreen else FlowMuted, modifier = Modifier.padding(end = 10.dp))
                                Text(column, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                Text(if (report != null) "Encontrado" else "Sin verificar", color = if (report != null) FlowGreen else FlowMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            error?.let { message -> item { FlowMessage("No se completó el análisis", message, Color(0xFFFF677B)) } }
            report?.let { data ->
                item {
                    FlowMessage(if (data.errorCount == 0) "Archivo leído" else "${data.errorCount} incidencias para revisar",
                        "${data.products.size} grupos de productos · ${data.serialRowCount} filas de serie. Las columnas reconocidas no certifican que el reporte esté completo.",
                        if (data.errorCount == 0) FlowGreen else FlowAmber)
                }
                item { TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.testTag("import-read-details")) {
                    Text(if (showDetails) "Ocultar resumen y errores" else "Ver resumen y errores de lectura", fontSize = 12.sp)
                } }
                if (showDetails) {
                    item { FlowCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Códigos únicos: ${data.uniqueProductCodeCount} · Grupos sin código: ${data.uncodedProductCount}")
                        Text("Series únicas sin espacios externos: ${data.uniqueSerialCount}")
                        Text("Series sin error: ${data.serialCount} · Resúmenes: ${data.summaryRows}")
                        Text("Se usa Cantidad; los resúmenes no se suman a las series.", color = FlowMuted, fontSize = 11.sp)
                    } } }
                    items(data.issues) { issue -> Text("${issue.sheet}${issue.row?.let { ", fila $it" }.orEmpty()}: ${issue.message}", color = FlowAmber, fontSize = 11.sp) }
                    if (data.errorCount > data.issues.size) item { Text("Se muestran ${data.issues.size} de ${data.errorCount} incidencias.", color = FlowMuted, fontSize = 11.sp) }
                    items(data.products.take(50)) { p -> Text("${p.code?.let { "[$it]" } ?: "[Sin código]"} ${p.name}\n${p.serials.size} filas de serie · ${p.sheet}, fila ${p.row}", color = FlowMuted, fontSize = 11.sp) }
                    if (data.products.size > 50) item { Text("Primeros 50 grupos del archivo.", color = FlowMuted, fontSize = 11.sp) }
                }
                item { FlowMessage("Proceso de solo lectura", if (comparisonReady)
                    "La comparación ya está disponible en memoria. Revisarla no vuelve a consultar el inventario."
                    else "Analizar consultará el inventario una vez: unos 24 561 documentos según la auditoría, más validaciones. Revisa tu cuota disponible. Máximo: 30 000 documentos por intento.", FlowMuted) }
            }
        }
        Button(onClick = onAnalyze, enabled = report != null, modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("import-analyze"),
            colors = ButtonDefaults.buttonColors(containerColor = FlowGreen, contentColor = Color(0xFF00152D), disabledContainerColor = Color(0xFF123B6C)), shape = RoundedCornerShape(12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(if (comparisonReady) "Revisar diferencias" else "Analizar archivo", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(if (comparisonReady) "Comparación conservada · sin nuevas lecturas" else "≈24 561 lecturas al comparar · sin escrituras", fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun ImportAnalysisScreen(fileName: String, fileBytes: Long?, report: ExcelInventory?, reading: Boolean,
    progress: ComparisonProgress?, onCancel: () -> Unit) {
    val local = progress?.collection == "comparación local"
    val productsDone = progress?.collection == "existencias" || local
    ImportFlowFrame("Analizando inventario", "Leyendo Excel y comparando el inventario", 2, onCancel) {
        LazyColumn(Modifier.weight(1f).testTag("import-analysis"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SelectedExcelCard(fileName.ifEmpty { "Preparando archivo…" }, fileBytes, null) }
            item { FlowCard { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                AnalysisStatus("Leyendo archivo Excel", if (reading) "Lectura local en curso…" else "${report?.serialRowCount ?: 0} filas de serie leídas", !reading && report != null, reading)
                AnalysisStatus("Validando columnas y cantidades", if (report == null) "Esperando lectura" else "${report.errorCount} incidencias detectadas · no se corrigen automáticamente", report != null, false, report?.errorCount?.let { it > 0 } == true)
                AnalysisStatus("Conectando con el inventario", if (progress != null) "Lectura autorizada iniciada" else if (reading) "Pendiente" else "Verificando acceso…", progress != null, !reading && progress == null)
                AnalysisStatus("Leyendo productos", "${progress?.products ?: 0} productos leídos", productsDone, progress?.collection == "productos")
                AnalysisStatus("Leyendo números de serie", "${progress?.existences ?: 0} existencias leídas", local, progress?.collection == "existencias")
                AnalysisStatus("Comparando y detectando conflictos", if (local) "Comparación local de códigos, series y cantidades…" else "Pendiente de completar la lectura", false, local)
            } } }
            item { FlowMessage("Proceso de solo lectura", "No se modifica el inventario. El progreso usa los documentos realmente leídos; el total actual no se conoce hasta terminar. Cancelar no recupera las lecturas ya consumidas.", FlowMuted) }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("import-cancel"), shape = RoundedCornerShape(12.dp)) { Text("Cancelar análisis", color = FlowMuted) }
    }
}

@Composable
private fun AnalysisStatus(title: String, message: String, done: Boolean, active: Boolean, warning: Boolean = false) {
    val tint = when { warning -> FlowAmber; done -> FlowGreen; active -> Color(0xFF008DFF); else -> FlowMuted }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = if (done || active) tint else Color(0xFF052548), border = BorderStroke(1.dp, tint)) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Text(if (warning) "!" else if (done) "✓" else "○", color = if (done || active) Color(0xFF00152D) else tint, fontSize = 15.sp) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (done) Text(if (warning) "Revisar" else "100%", color = tint, fontSize = 10.sp)
            }
            if (active) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = tint, trackColor = Color(0xFF073461))
            else LinearProgressIndicator(progress = { if (done) 1f else 0f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = tint, trackColor = Color(0xFF073461))
            Text(message, color = FlowMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun SelectedExcelCard(name: String, size: Long?, onClear: (() -> Unit)?) {
    FlowCard { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ImportExcelSymbol(Modifier.size(36.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(size?.let { if (it < 1024) "$it B" else "${(it + 1023) / 1024} KB" } ?: "Tamaño no disponible", color = FlowMuted, fontSize = 10.sp)
        }
        onClear?.let { IconButton(onClick = it, modifier = Modifier.testTag("import-clear")) { Text("×", color = FlowMuted, fontSize = 25.sp) } }
    } }
}

@Composable
private fun FlowCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF02162E), border = BorderStroke(1.dp, FlowBorder), content = content)
}

@Composable
private fun FlowMessage(title: String, message: String, tint: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = .08f), border = BorderStroke(1.dp, tint.copy(alpha = .5f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(message, color = FlowMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
internal fun ImportExcelSymbol(modifier: Modifier = Modifier.size(28.dp)) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRoundRect(Color(0xFF35C97E), Offset(w * .28f, h * .05f), Size(w * .7f, h * .9f), CornerRadius(w * .08f))
            drawRect(Color(0xFF108844), Offset(w * .55f, h * .35f), Size(w * .43f, h * .3f))
            drawRoundRect(Color(0xFF006B38), Offset(0f, h * .2f), Size(w * .68f, h * .65f), CornerRadius(w * .06f))
        }
        Text("X", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = (-4).dp))
    }
}
