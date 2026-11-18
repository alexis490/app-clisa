package com.example.app_clisa.ui.products

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.app_clisa.ui.registration.RegisterIcon
import org.json.JSONObject

private val Navy = Color(0xFF00162C)
private val Blue = Color(0xFF0061FF)
private val Green = Color(0xFF00E6A0)
private val Muted = Color(0xFFA9C8EC)
private val Border = Color(0xFF07568B)
private val Amber = Color(0xFFFFBC39)
private val Red = Color(0xFFFF3559)
private val Purple = Color(0xFF9A44FF)

/** Compact visual shell from the supplied inventory mockup. No workflow logic lives here. */
@Composable
internal fun InventoryImportFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Blue, secondary = Green, surface = Navy, background = Color(0xFF000B1A), onSurface = Color.White)) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF00132F), Color(0xFF000915))))
                .statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(52.dp).drawBehind {
                    drawPath(Path().apply { moveTo(size.width*.68f, size.height); lineTo(size.width*.84f, 0f); lineTo(size.width,0f); lineTo(size.width,size.height); close() }, Color(0xFF002152).copy(alpha=.5f))
                }, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("import-back")) { RegisterIcon("back", Color.White, "Volver") }
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 12.dp))
                }
                content()
            }
            }
        }
    }
}

@Composable
internal fun InventoryCard(modifier: Modifier = Modifier, tint: Color = Border, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Navy, shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, tint)) {
        Column(Modifier.background(Brush.linearGradient(listOf(tint.copy(alpha = .12f), Color.Transparent))).padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

@Composable
private fun Badge(symbol: String, tint: Color, large: Boolean = false) {
    Surface(color = tint, shape = if (large) CircleShape else RoundedCornerShape(7.dp)) {
        Box(Modifier.size(if (large) 52.dp else 32.dp), contentAlignment = Alignment.Center) {
            ImportGlyph(symbol, if (tint == Green || tint == Amber) Color(0xFF00223A) else Color.White, Modifier.size(if (large) 30.dp else 21.dp))
        }
    }
}

@Composable
private fun ImportGlyph(symbol: String, color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = Stroke(w * .075f)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(w*x,h*y), Offset(w*xx,h*yy), w*.075f)
        when (symbol) {
            "✓" -> { line(.15f,.5f,.4f,.75f); line(.4f,.75f,.85f,.22f) }
            "!" -> { drawCircle(color, w*.38f, style=stroke); line(.5f,.25f,.5f,.55f); drawCircle(color,w*.045f, Offset(w*.5f,h*.72f)) }
            "◷" -> { drawCircle(color,w*.4f,style=stroke); line(.5f,.2f,.5f,.5f); line(.5f,.5f,.72f,.65f) }
            "♙", "♧" -> { // grouped inventory boxes / new product
                drawRect(color,Offset(w*.15f,h*.4f),Size(w*.3f,h*.4f),style=stroke)
                drawRect(color,Offset(w*.55f,h*.4f),Size(w*.3f,h*.4f),style=stroke)
                drawRect(color,Offset(w*.35f,h*.1f),Size(w*.3f,h*.3f),style=stroke)
                line(.3f,.4f,.3f,.55f); line(.7f,.4f,.7f,.55f)
                if(symbol=="♧") { line(.65f,.16f,.95f,.16f); line(.8f,.02f,.8f,.3f) }
            }
            "⚒" -> { line(.2f,.15f,.8f,.85f); line(.8f,.15f,.2f,.85f); drawCircle(color,w*.13f,Offset(w*.2f,h*.15f),style=stroke); drawCircle(color,w*.13f,Offset(w*.8f,h*.15f),style=stroke) }
            "↓" -> { line(.5f,.05f,.5f,.7f); line(.3f,.5f,.5f,.7f); line(.5f,.7f,.7f,.5f); line(.15f,.65f,.15f,.9f); line(.15f,.9f,.85f,.9f); line(.85f,.9f,.85f,.65f) }
            "⌂" -> { line(.1f,.45f,.5f,.1f); line(.5f,.1f,.9f,.45f); line(.2f,.4f,.2f,.9f); line(.2f,.9f,.8f,.9f); line(.8f,.9f,.8f,.4f); drawRect(color,Offset(w*.4f,h*.6f),Size(w*.2f,h*.3f),style=stroke) }
            "←" -> { line(.1f,.5f,.9f,.5f); line(.1f,.5f,.4f,.2f); line(.1f,.5f,.4f,.8f) }
            "compare" -> { line(.1f,.3f,.9f,.3f); line(.7f,.1f,.9f,.3f); line(.9f,.3f,.7f,.5f); line(.9f,.7f,.1f,.7f); line(.1f,.7f,.3f,.5f); line(.1f,.7f,.3f,.9f) }
            "series" -> { drawCircle(color,w*.24f,Offset(w*.35f,h*.35f),style=stroke); drawCircle(color,w*.24f,Offset(w*.65f,h*.65f),style=stroke); line(.35f,.35f,.65f,.65f) }
            "cloud" -> { val p=Path().apply { moveTo(w*.2f,h*.75f); cubicTo(-w*.1f,h*.65f,w*.1f,h*.25f,w*.32f,h*.38f); cubicTo(w*.32f,-h*.05f,w*.88f,h*.08f,w*.8f,h*.42f); cubicTo(w*1.08f,h*.4f,w*1.04f,h*.8f,w*.8f,h*.8f) }; drawPath(p,color,style=stroke); line(.5f,.4f,.5f,.9f); line(.33f,.72f,.5f,.9f); line(.5f,.9f,.67f,.72f) }
            "0" -> { drawCircle(color,w*.38f,style=stroke); line(.5f,.3f,.5f,.7f) }
            else -> { val p=Path().apply { moveTo(w*.2f,h*.1f); lineTo(w*.65f,h*.1f); lineTo(w*.85f,h*.3f); lineTo(w*.85f,h*.9f); lineTo(w*.2f,h*.9f); close() }; drawPath(p,color,style=stroke); line(.65f,.1f,.65f,.3f); line(.65f,.3f,.85f,.3f); line(.35f,.5f,.7f,.5f); line(.35f,.65f,.7f,.65f) }
        }
    }
}

@Composable
private fun ImportButton(label: String, green: Boolean = false, tag: String = "", action: () -> Unit) {
    Button(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(tag), shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (green) Green else Blue, contentColor = if (green) Color(0xFF00152D) else Color.White)) {
        val parts = label.split("  ", limit = 2)
        if (parts.size == 2 && parts[0].length <= 2) { ImportGlyph(if (green) "cloud" else parts[0], if (green) Navy else Color.White); Spacer(Modifier.width(8.dp)); Text(parts[1], fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        else Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun InventorySelection(name: String, bytes: Long, detected: Set<String> = emptySet(), checking: Boolean = false, ready: Boolean = false, enabled: Boolean = true, onPick: () -> Unit) {
    var example by remember { mutableStateOf(false) }
    InventoryCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            ImportExcelSymbol(Modifier.size(62.dp))
            Text("Selecciona un archivo Excel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Formatos soportados: .xlsx, .xls, .csv\nDebe contener productos, series y cantidades.", fontSize = 12.sp, lineHeight = 17.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (name.isNotBlank()) Text("$name · ${bytes / 1024} KB", fontSize = 11.sp, color = Muted)
            Button(onClick = onPick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("automatic-import-primary"), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)) { ImportGlyph("▧", Color.White); Spacer(Modifier.width(8.dp)); Text("Seleccionar archivo", fontSize = 13.sp) }
        }
    }
    InventoryCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ⓘ  Formato esperado", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Surface(color = if (ready) Green.copy(alpha = .12f) else Color(0xFF1A3048), shape = RoundedCornerShape(12.dp)) { Text(if (ready) "✓ Válido" else if (checking) "Comprobando…" else "Pendiente", fontSize = 10.sp, color = if (ready) Green else Muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
        }
        listOf("serial" to "Lote / N° de serie", "code" to "Referencia interna", "name" to "Producto", "quantity" to "Cantidad / existencias").forEach { (key, label) ->
            val found = key in detected
            Row(Modifier.testTag("format-check-$key").semantics { stateDescription = if (found) "Detectado" else "Pendiente" }, horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (found) Green else Color(0xFF54687F), shape = CircleShape) { ImportGlyph("✓", if (found) Navy else Color(0xFF9CB0C5), Modifier.padding(2.dp).size(11.dp)) }; Text(label, fontSize = 13.sp)
            }
        }
        if (name.isNotBlank()) Text("Grupos [código] producto, series y cantidades.", fontSize = 11.sp, color = Muted)
        TextButton(onClick = { example = true }, contentPadding = PaddingValues(0.dp)) { Text("ⓘ  Ver ejemplo de archivo", color = Color(0xFF00BAFF), fontSize = 13.sp) }
    }
    if (example) AlertDialog(onDismissRequest = { example = false }, title = { Text("Ejemplo de archivo") }, text = { Text("Lote / N° de serie | Referencia interna | Producto | Cantidad\n[PRD-001] Producto de ejemplo | | | 3\nSN-001 | PRD-001 | Producto de ejemplo | 3") }, confirmButton = { TextButton(onClick = { example = false }) { Text("Cerrar") } })
}

@Composable
internal fun InventoryAnalyzeButton(enabled: Boolean, checking: Boolean, selected: Boolean, onAnalyze: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = onAnalyze, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("analyze-excel"), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Navy, disabledContainerColor = Color(0xFF29435F), disabledContentColor = Color(0xFFA4B9D0))) {
            RegisterIcon("search", if (enabled) Navy else Color(0xFFA4B9D0), null, size = 20.dp); Spacer(Modifier.width(8.dp)); Text("Analizar archivo", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(if (checking) "Comprobando los campos del archivo…" else if (enabled) "Formato detectado. Pulsa Analizar archivo para continuar." else if (selected) "Completa el formato requerido para poder analizar." else "Primero selecciona un archivo Excel.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
internal fun InventorySelectionSteps(active: Int = 1, description: String = "Sincroniza tus productos y existencias desde un archivo Excel") {
    Text(description, fontSize = 11.sp, lineHeight = 14.sp, color = Muted, modifier = Modifier.padding(start = 48.dp, end = 16.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).drawBehind {
        drawLine(Border, Offset(size.width * .1f, 14.5.dp.toPx()), Offset(size.width * .9f, 14.5.dp.toPx()), strokeWidth = 1.dp.toPx())
    }) {
        listOf("Seleccionar", "Analizar", "Revisar", "Clasificar", "Sincronizar").forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val current = index + 1 == active
                Box(Modifier.size(29.dp).then(if (current) Modifier.border(1.dp, Green, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = if (index + 1 <= active) Green else Navy, border = BorderStroke(1.dp, if (index + 1 <= active) Green else Border)) { Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) { if (index + 1 < active) ImportGlyph("✓", Navy, Modifier.size(14.dp)) else Text("${index + 1}", fontSize = 12.sp, color = if (current) Navy else Muted) } }
                }
                Text(label, fontSize = 9.sp, lineHeight = 12.sp, color = if (current) Green else Muted, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

@Composable
internal fun InventoryExcelAnalysis(job: JSONObject?, filename: String, bytes: Long) {
    val known = job != null
    val a = job?.optJSONObject("analysis")
    val total = job?.optInt("total") ?: 0
    val cursor = job?.optInt("cursor") ?: 0
    val completed = job?.optString("status") in setOf("REVIEW_READY", "CLASSIFICATION", "PREVIEW", "SYNCING", "COMPLETED", "PARTIAL")
    val rows = a?.optInt("rowsRead") ?: job?.optInt("rowCount")
    val compared = a?.optInt("productsCompared") ?: if (completed) total else cursor
    fun ratio(done: Int, count: Int): Float? = if (!known) null else if (count == 0) 1f else (done.toFloat() / count).coerceIn(0f, 1f)
    InventoryCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ImportExcelSymbol(Modifier.size(34.dp))
            Column(Modifier.weight(1f)) {
                Text(job?.optString("filename") ?: filename.ifBlank { "Archivo Excel" }, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
                val length = job?.optLong("bytes") ?: bytes
                Text(if (length > 0) "${length / 1024} KB" else "Preparando archivo…", fontSize = 11.sp, lineHeight = 14.sp, color = Muted)
            }
        }
    }
    InventoryCard {
        AnalysisProgressRow("Leyendo archivo Excel…", if (known) "$rows filas leídas" else "Esperando la lectura del servidor…", if (known) 1f else null, "analysis-reading")
        AnalysisProgressRow("Validando datos…", if (a != null) "${a.optInt("validProducts")} productos válidos en origen" else if (known) "Validación de origen completada" else "Pendiente de lectura del archivo", if (known) 1f else 0f, "analysis-validation")
        AnalysisProgressRow("Consultando inventario actual…", if (a != null) "${a.optInt("inventoryChecked")} de ${a.optInt("inventoryTarget")} productos comprobados" else "Pendiente de consultar Firestore", if (a != null) ratio(a.optInt("inventoryChecked"), a.optInt("inventoryTarget")) else if (!known) 0f else null, "analysis-inventory")
        AnalysisProgressRow("Comparando información…", if (known) "$compared de $total productos" else "Pendiente de analizar el archivo", if (known) ratio(compared, total) else 0f, "analysis-comparison", "compare")
        AnalysisProgressRow("Analizando números de serie…", if (a != null) "${a.optInt("seriesProcessed")} de ${a.optInt("seriesTotal")} series revisadas o apartadas" else "Pendiente de comprobar las series", if (a != null) ratio(a.optInt("seriesProcessed"), a.optInt("seriesTotal")) else if (!known) 0f else null, "analysis-serials", "series")
        AnalysisProgressRow("Detectando duplicados y conflictos…", if (known) "${job!!.optInt("issueCount")} incidencias de origen · ${a?.optInt("excludedProducts") ?: 0} productos apartados" else "Pendiente de comprobar incidencias", if (completed) 1f else if (known) ratio(a?.optInt("conflictsChecked") ?: cursor, total) else 0f, "analysis-conflicts", "!", Color(0xFFFF9AAE))
    }
}

@Composable
private fun AnalysisProgressRow(title: String, detail: String, progress: Float?, tag: String, symbol: String = "♙", tint: Color = Color(0xFF00AFFF)) {
    val done = progress == 1f
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag(tag), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = if (done) Green else if (progress == 0f) Color(0xFF315574) else tint) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { ImportGlyph(if (done) "✓" else symbol, if (done || symbol == "!") Navy else Color.White, Modifier.size(17.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress == null) LinearProgressIndicator(modifier = Modifier.weight(1f).height(6.dp), color = Color(0xFF00B8FF), trackColor = Color(0xFF173953))
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(6.dp), color = if (done) Green else Color(0xFF00B8FF), trackColor = Color(0xFF173953), drawStopIndicator = {})
                Text(progress?.let { "${(it * 100).toInt()}%" } ?: "—", fontSize = 11.sp, lineHeight = 13.sp, color = Muted, modifier = Modifier.width(32.dp))
            }
            Text(detail, fontSize = 11.sp, lineHeight = 13.sp, color = Muted)
        }
    }
}

@Composable
internal fun InventoryAnalysisFooter(onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InventoryCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("ⓘ", color = Color(0xFF00B8FF), fontSize = 22.sp)
                Text("No se está modificando el inventario.\nEste proceso es solo de lectura.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(43.dp).testTag("cancel-excel-analysis"), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Red), colors = ButtonDefaults.outlinedButtonColors(containerColor = Red.copy(alpha = .1f), contentColor = Red)) { Text("Cancelar", fontSize = 13.sp) }
    }
}

@Composable
internal fun InventoryReviewReady(job: JSONObject, onIssues: () -> Unit) {
    val report = job.optJSONObject("review")
    val quality = report?.optJSONObject("quality")
    val comparison = report?.optJSONObject("comparison")
    val validation = report?.optJSONObject("validation")
    ReviewBlock {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) { Text("Resumen del análisis", fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold); Text(job.optString("filename"), fontSize = 11.sp, lineHeight = 15.sp, color = Muted) }
            ReviewPill("Analizado", Green)
        }
        listOf(listOf(Stat(reviewNumber(report,"rowsProcessed",job.optInt("rowCount")),"Filas procesadas",Blue,"▧"),Stat(reviewNumber(report,"uniqueProducts"),"Productos únicos",Blue,"♙")),
            listOf(Stat(reviewNumber(report,"newProducts"),"Productos nuevos",Green,"♧"),Stat(reviewNumber(report,"issueCount"),"Incidencias detectadas",Red,"!"))).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { pair.forEach { s ->
                Surface(Modifier.weight(1f), color = s.tint.copy(alpha = .08f), border = BorderStroke(1.dp, s.tint.copy(alpha = .8f)), shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ImportGlyph(s.icon, if (s.tint == Blue) Muted else s.tint, Modifier.size(25.dp))
                        Column(Modifier.weight(1f)) { Text(s.value, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold); Text(s.label, fontSize = 10.sp, lineHeight = 13.sp) }
                    }
                }
            } }
        }
    }
    ReviewBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("1. Calidad de datos", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ReviewPill("${reviewNumber(report,"issueCount")} incidencias", Amber)
        }
        Column {
            ReviewCountRow("Códigos duplicados", reviewNumber(quality,"duplicateCodes"), Red, "▧")
            ReviewCountRow("Series duplicadas", reviewNumber(quality,"duplicateSeries"), Amber, "series")
            ReviewCountRow("Series vacías", reviewNumber(quality,"emptySeries"), Amber, "!")
            ReviewCountRow(if (quality?.optBoolean("negativeOnly",true) != false) "Cantidades negativas" else "Cantidades inválidas", reviewNumber(quality,"negativeQuantities"), Red, "!")
            ReviewCountRow("Otros errores", reviewNumber(quality,"otherErrors"), Muted, "!")
        }
        OutlinedButton(onClick = onIssues, enabled = report != null, modifier = Modifier.fillMaxWidth().height(34.dp).testTag("review-product-issues"), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(2.dp), border = BorderStroke(1.dp, Border)) { Text("Ver incidencias por producto  ›", color = Color.White, fontSize = 12.sp, lineHeight = 15.sp) }
    }
    ReviewBlock {
        Text("2. Comparación con Firebase", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
        val bars = listOf(Triple("Sin cambios","unchanged",Green),Triple("Stock aumentado","increased",Color(0xFF008DFF)),Triple("Stock disminuido","decreased",Amber),Triple("Productos nuevos","newProducts",Purple),Triple("Ausentes del Excel","absent",Muted))
        val max = bars.maxOf { comparison?.optInt(it.second) ?: 0 }.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { bars.forEach { (label,key,tint) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row { Text(label, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f)); Text(reviewNumber(comparison,key), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold) }
                LinearProgressIndicator(progress = { (comparison?.optInt(key) ?: 0).toFloat().div(max).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth().height(6.dp), color = tint, trackColor = Color(0xFF0B2946), drawStopIndicator = {})
            }
        } }
        if (comparison != null && comparison.isNull("absent")) Text("Ausentes: total no disponible en esta revisión.", color = Muted, fontSize = 10.sp, lineHeight = 13.sp)
        if ((comparison?.optInt("unknownStock") ?: 0) > 0) Text("${comparison!!.optInt("unknownStock")} productos sin stock previo verificable.", color = Amber, fontSize = 10.sp, lineHeight = 13.sp)
    }
    ReviewBlock {
        Text("3. Resultado de validación", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
        Column { ReviewCountRow("Registros válidos", reviewNumber(validation,"validRecords"), Green,"✓"); ReviewCountRow("Registros separados",reviewNumber(validation,"separatedRecords"),Red,"!") }
        if (report == null) Text("Preparando resumen…", color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

private fun reviewNumber(value: JSONObject?, key: String, fallback: Int? = null): String {
    val n = if (value != null && value.has(key) && !value.isNull(key)) value.optLong(key) else fallback?.toLong()
    return n?.let { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) } ?: "—"
}
@Composable private fun ReviewBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = Navy, border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(9.dp)) { Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content) }
}
@Composable private fun ReviewPill(text: String, tint: Color) {
    Surface(color = tint.copy(alpha = .12f), shape = RoundedCornerShape(7.dp), border = BorderStroke(1.dp,tint.copy(alpha=.4f))) { Text(text, color=tint,fontSize=10.sp,lineHeight=13.sp,modifier=Modifier.padding(horizontal=6.dp,vertical=4.dp)) }
}
@Composable private fun ReviewCountRow(label: String, count: String, tint: Color, icon: String) {
    Row(Modifier.fillMaxWidth().padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ImportGlyph(icon,tint,Modifier.size(16.dp));Text(label,fontSize=12.sp,lineHeight=16.sp,modifier=Modifier.weight(1f));Text(count,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.SemiBold)
    }
}
@Composable internal fun InventoryReviewFooter(enabled: Boolean, onContinue: () -> Unit) {
    Button(onClick=onContinue,enabled=enabled,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp).height(48.dp).testTag("continue-to-classification"),shape=RoundedCornerShape(9.dp),colors=ButtonDefaults.buttonColors(containerColor=Blue,contentColor=Color.White)) { Text("Continuar a clasificación  →",fontSize=15.sp,fontWeight=FontWeight.SemiBold) }
}
@Composable internal fun InventoryClassificationStage(job: JSONObject) {
    val complete = job.optString("status") == "PREVIEW"
    val counts = job.optJSONObject("classificationProgress")
    val total = job.optJSONObject("review")?.optInt("validProducts") ?: job.optInt("total")
    val processed = counts?.optInt("processed")
    val fraction = if (complete) 1f else if (processed != null && total > 0) processed.toFloat() / total else if (job.optInt("total") > 0) job.optInt("cursor").toFloat() / job.optInt("total") else 0f
    val pending = counts?.optInt("pending") ?: job.optJSONObject("preview")?.optInt("classificationPending") ?: 0
    fun number(key: String) = counts?.let { String.format(java.util.Locale.US, "%,d", it.optInt(key)) } ?: "—"
    InventoryCard(tint = Color(0xFF008DFF)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClassificationIcon("sparkles", Color(0xFF00CFFF), Modifier.size(32.dp))
            Text(if (!complete) "Clasificando productos…" else if (pending > 0) "Clasificación con información pendiente" else "Clasificación completada", modifier = Modifier.weight(1f), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            Text("${(fraction.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).height(9.dp), color = Green, trackColor = Color(0xFF052D53))
        Text(if (processed != null) "${String.format(java.util.Locale.US, "%,d", processed)} de ${String.format(java.util.Locale.US, "%,d", total)} productos" else "${if (complete) job.optInt("total") else job.optInt("cursor")} de ${job.optInt("total")} productos revisados", color = Muted, fontSize = 13.sp)
    }
    InventoryCard {
        val rows = listOf("Aplicando reglas de clasificación", "Procesando con IA", "Preparando datos clasificados")
        rows.forEachIndexed { index, label ->
            val done = complete
            val active = !complete && index < 2
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (done) Surface(color = Green, shape = CircleShape) { Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { ImportGlyph("✓", Navy) } }
                else if (active) CircularProgressIndicator(modifier = Modifier.size(30.dp), color = Green, trackColor = Border, strokeWidth = 3.dp)
                else Box(Modifier.size(30.dp).border(3.dp, Muted, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label, fontSize = 14.sp, lineHeight = 18.sp)
                    if (index < 2) Text("${number(if (index == 0) "rulesProcessed" else "aiProcessed")} productos procesados", fontSize = 12.sp, color = Muted)
                }
                Text(if (done) "Completado" else if (active) "En progreso" else "Pendiente", fontSize = 11.sp, color = if (done) Green else if (active) Amber else Muted)
            }
            if (index < 2) HorizontalDivider(color = Border.copy(alpha = .35f), modifier = Modifier.padding(start = 42.dp))
        }
    }
    InventoryCard {
        Text("Resultados preliminares", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(Triple("byRules", "Por reglas", Green), Triple("byAi", "Por IA", Color(0xFF00CFFF)), Triple("pending", "Pendientes", Amber)).forEachIndexed { index, (key, label, tint) ->
                Surface(modifier = Modifier.weight(1f), color = Color(0xFF00223B), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Border.copy(alpha = .65f))) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ClassificationIcon(if (index == 0) "cube" else if (index == 1) "sparkles" else "pending", tint, Modifier.size(23.dp))
                            Text(number(key), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(label, fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }
    }
    if (counts == null) Text("El desglose por reglas e IA no está disponible para este expediente anterior.", fontSize = 12.sp, color = Muted)
    if (complete && pending > 0) Text("$pending productos requieren información adicional. Revisa los pendientes en el siguiente paso.", fontSize = 12.sp, color = Amber)
    job.optString("catalogWarning").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, fontSize = 12.sp, color = Amber) }
}

@Composable internal fun InventoryClassificationFooter(enabled: Boolean, onSummary: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val outline = if (enabled) listOf(Color(0xFFFFCE35), Color(0xFFFF3C16)) else listOf(Border, Border)
        Surface(onClick = onSummary, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("classification-to-summary").background(Brush.horizontalGradient(outline), RoundedCornerShape(20.dp)).padding(2.dp), shape = RoundedCornerShape(18.dp), color = Color(0xFF080F1B), contentColor = if (enabled) Color.White else Muted) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ClassificationIcon("flame", Amber, Modifier.size(33.dp))
                Text("Sincronizar con Firebase", modifier = Modifier.weight(1f), fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                Text("›", fontSize = 32.sp)
            }
        }
        Text(if (enabled) "Revisa la clasificación en el resumen antes de confirmar." else "Espera a que termine la clasificación para continuar.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable private fun ClassificationIcon(kind: String, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun shape(points: List<Pair<Float, Float>>, color: Color) = drawPath(Path().apply { points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x*w,y*h) else lineTo(x*w,y*h) }; close() }, color)
        when (kind) {
            "sparkles" -> listOf(Triple(.35f,.5f,.32f), Triple(.77f,.19f,.16f), Triple(.78f,.81f,.18f)).forEach { (x,y,r) -> shape(listOf(x to y-r, x+r*.3f to y-r*.3f, x+r to y, x+r*.3f to y+r*.3f, x to y+r, x-r*.3f to y+r*.3f, x-r to y, x-r*.3f to y-r*.3f), tint) }
            "cube" -> { shape(listOf(.05f to .25f,.5f to .04f,.95f to .25f,.5f to .5f), tint); shape(listOf(.05f to .31f,.46f to .55f,.46f to .98f,.05f to .73f), tint.copy(alpha=.8f)); shape(listOf(.54f to .55f,.95f to .31f,.95f to .73f,.54f to .98f), tint.copy(alpha=.55f)) }
            "flame" -> {
                drawPath(Path().apply { moveTo(.48f*w,0f); cubicTo(.25f*w,.25f*h,.29f*w,.4f*h,.35f*w,.5f*h); cubicTo(.05f*w,.25f*h,.03f*w,.8f*h,.35f*w,.94f*h); cubicTo(.9f*w,1.2f*h,1.1f*w,.56f*h,.48f*w,0f); close() }, Color(0xFFFF4A00))
                drawPath(Path().apply { moveTo(.21f*w,.4f*h); cubicTo(-.12f*w,.42f*h,.02f*w,1.1f*h,.62f*w,.96f*h); cubicTo(.58f*w,.62f*h,.4f*w,.47f*h,.21f*w,.4f*h); close() }, Color(0xFFFFC000))
            }
            else -> { shape(listOf(.15f to .03f,.65f to .03f,.9f to .28f,.9f to .95f,.15f to .95f), tint); drawCircle(Navy, w*.23f, Offset(w*.68f,h*.72f)); drawCircle(tint,w*.18f,Offset(w*.68f,h*.72f),style=Stroke(w*.04f));drawLine(tint,Offset(w*.68f,h*.6f),Offset(w*.68f,h*.73f),w*.04f);drawLine(tint,Offset(w*.68f,h*.73f),Offset(w*.78f,h*.79f),w*.04f) }
        }
    }
}

@Composable internal fun InventoryProductIssues(job: JSONObject) {
    val issues=job.optJSONArray("allIssues")
    val groups=remember(job){(0 until (issues?.length() ?: 0)).mapNotNull { issues?.optJSONObject(it) }.groupBy { i ->
        i.optString("productCode").takeIf { it.isNotBlank()&&it!="null" }?.let { "code:$it" } ?: "row:${i.optString("sheet")}:${i.optInt("row")}"
    }.entries.sortedBy { it.key }}
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(60) }
    var selected by remember { mutableStateOf<List<JSONObject>?>(null) }
    OutlinedTextField(query,{query=it},placeholder={Text("Buscar por código, producto o incidencia",fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(8.dp))
    val filtered=groups.filter { query.isBlank()||it.value.any { i->i.toString().contains(query,true) } }
    filtered.take(limit).forEach { entry ->
        val first=entry.value.first()
        InventoryCard(Modifier.clickable { selected=entry.value }) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Badge("!",Red)
                Column(Modifier.weight(1f)) {
                    Text(first.optString("productName").takeIf { it.isNotBlank()&&it!="null" } ?: "Producto sin identificar",fontSize=13.sp,lineHeight=17.sp,fontWeight=FontWeight.SemiBold)
                    Text("Código: ${first.optString("productCode").takeIf { it.isNotBlank()&&it!="null" } ?: "Sin código"}",fontSize=11.sp,lineHeight=15.sp,color=Muted)
                }
                Text("${entry.value.size}  ›",fontSize=13.sp,color=Amber)
            }
        }
    }
    if(filtered.isEmpty())Text("No hay incidencias que coincidan con la búsqueda.",color=Muted,fontSize=12.sp)
    if(filtered.size>limit)TextButton(onClick={limit+=60}){Text("Ver más productos")}
    selected?.let { items -> InventoryImportFrame("Incidencias del producto",{selected=null}) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val first=items.first()
            Text(first.optString("productName").takeIf{it!="null"&&it.isNotBlank()} ?: "Producto sin identificar",fontWeight=FontWeight.Bold)
            Text("Código: ${first.optString("productCode").takeIf{it!="null"&&it.isNotBlank()} ?: "Sin código"}",fontSize=12.sp,color=Muted)
            var shown by remember(items){mutableIntStateOf(100)}
            items.take(shown).forEach { i -> InventoryCard {
                Text(issueTitle(i.optString("type")),color=Amber,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
                Text("${i.optString("sheet")} · Fila ${i.optInt("row")}",fontSize=12.sp,color=Muted)
                if(i.optString("productName")!=first.optString("productName"))Text("Producto: ${i.optString("productName")}",fontSize=12.sp)
                i.optString("serial").takeIf{it!="null"&&it.isNotBlank()}?.let { Text("Serie: $it",fontSize=12.sp) }
                Text(i.optString("description"),fontSize=12.sp,lineHeight=17.sp)
                Text(i.optString("reason"),fontSize=11.sp,lineHeight=15.sp,color=Muted)
            } }
            if(items.size>shown)TextButton(onClick={shown+=100}){Text("Ver más incidencias")}
        }
        TextButton(onClick={selected=null},modifier=Modifier.fillMaxWidth()){Text("Volver a productos")}
    } }
}

@Composable
internal fun InventoryProcessing(status: String, cursor: Int, total: Int) {
    val active = when (status) { "COMPARISON" -> 2; "CLASSIFICATION" -> 3; "SYNCING" -> 4; else -> 0 }
    val titles = listOf("Lectura del archivo", "Validación de datos", "Comparación con Firebase", "Clasificación de productos")
    val descriptions = listOf("Leyendo el archivo y reconociendo columnas…", "Comprobando productos, series y cantidades…", "Consultando y comparando existencias…", "Aplicando reglas y Gemini…")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        titles.forEachIndexed { i, title ->
            Row(Modifier.fillMaxWidth().background(if (i == active) Blue.copy(alpha = .13f) else Color.Transparent).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = CircleShape, color = if (i < active) Green else if (i == active) Blue else Navy, border = BorderStroke(1.dp, if (i == active) Color.White else Border)) {
                        Box(Modifier.size(29.dp), contentAlignment = Alignment.Center) { Text("${i + 1}", fontWeight = FontWeight.Bold, color = if (i < active) Navy else Color.White) }
                    }
                    if (i < 3) Box(Modifier.width(1.dp).height(55.dp).background(Border))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row { Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(if (i < active) "100%" else if (i == active && total > 0) "${cursor * 100 / total}%" else "0%", fontSize = 12.sp, color = if (i < active) Green else Muted) }
                    Text(if (i == active && total > 0) "$cursor de $total productos" else descriptions[i], fontSize = 12.sp, color = Muted)
                    if (i >= active) LinearProgressIndicator(progress = { if (i == active && total > 0) cursor.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Blue, trackColor = Color(0xFF1A3555))
                }
            }
        }
    }
}

private data class Stat(val value: String, val label: String, val tint: Color, val icon: String)
@Composable
private fun Stats(stats: List<Stat>, onClick: (() -> Unit)? = null) {
    stats.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { s ->
                InventoryCard(Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), s.tint.copy(alpha = .6f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(s.icon, s.tint)
                        Column(Modifier.weight(1f)) { Text(s.value, fontSize = 19.sp, fontWeight = FontWeight.Bold); Text(s.label, fontSize = 11.sp, lineHeight = 13.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, tint: Color = Green, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = tint, shape = CircleShape) { ImportGlyph(if (tint == Green) "✓" else "!", Navy, Modifier.padding(2.dp).size(10.dp)) }; Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(value, fontSize = 12.sp)
    }
}

@Composable internal fun InventorySyncPanel(job: JSONObject, connection: String, onRetry: () -> Unit, onProducts: () -> Unit, onClassification: () -> Unit, onIssues: () -> Unit) {
    val syncing = job.optString("status") == "SYNCING"
    val total = job.optInt("total").coerceAtLeast(1)
    val fraction = if (syncing) job.optInt("cursor").toFloat() / total else 0f
    val counts = job.optJSONObject("preview") ?: JSONObject()
    val omitted = counts.optInt("productsOmitted")
    val valid = (total - omitted).coerceAtLeast(0)
    fun number(n: Int) = String.format(java.util.Locale.US, "%,d", n)
    InventoryCard(tint = Color(0xFF008DFF)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(23.dp)) {
                val stroke=Stroke(2.5.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)
                drawPath(Path().apply{moveTo(size.width*.4f,size.height*.3f);cubicTo(size.width*.8f,-size.height*.15f,size.width*1.15f,size.height*.2f,size.width*.7f,size.height*.6f)},Color(0xFF009BFF),style=stroke)
                drawPath(Path().apply{moveTo(size.width*.6f,size.height*.7f);cubicTo(size.width*.2f,size.height*1.15f,-size.width*.15f,size.height*.8f,size.width*.3f,size.height*.4f)},Color(0xFF009BFF),style=stroke)
                drawLine(Color(0xFF009BFF),Offset(size.width*.35f,size.height*.65f),Offset(size.width*.65f,size.height*.35f),2.5.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Text("Servicios conectados", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Firebase", "Render").forEach { service ->
                Surface(modifier = Modifier.weight(1f), color = Color(0xFF00213C), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Border)) {
                    Row(Modifier.padding(7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment=Alignment.CenterVertically) {
                        if (service == "Firebase") ClassificationIcon("flame", Amber, Modifier.size(19.dp))
                        else Canvas(Modifier.size(19.dp)) { drawRoundRect(Color.Black, cornerRadius=androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()));drawCircle(Color.White,size.width*.2f,Offset(size.width*.7f,size.height*.3f));drawRoundRect(Color.White,topLeft=Offset(size.width*.2f,size.height*.45f),size=Size(size.width*.4f,size.height*.4f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()));drawLine(Color.White,Offset(size.width*.4f,size.height*.6f),Offset(size.width*.7f,size.height*.3f),size.width*.24f) }
                        Text(service, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines=1, modifier=Modifier.weight(1f))
                        val tint = if (connection == "CONNECTED") Green else if (connection == "CHECKING") Muted else Amber
                        Text(if (connection == "CONNECTED") "✓ Conectado" else if (connection == "CHECKING") "Comprobando…" else "Sin verificar", color = tint, fontSize = 9.sp, modifier = Modifier.background(tint.copy(alpha=.12f),RoundedCornerShape(6.dp)).padding(horizontal=4.dp,vertical=5.dp))
                    }
                }
            }
        }
        if (connection == "ERROR") TextButton(onClick=onRetry) { Text("Reintentar conexión") }
    }
    Text("Resumen a sincronizar", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Triple(valid,"Productos",Blue),Triple(counts.optInt("productsUpdated"),"Actualizaciones",Green),Triple(counts.optInt("productsCreated"),"Nuevos",Purple),Triple(omitted,"Omitidos",Red)).forEachIndexed { index, (n,label,tint) ->
            Surface(modifier=Modifier.weight(1f),color=tint.copy(alpha=.13f),shape=RoundedCornerShape(10.dp),border=BorderStroke(1.dp,tint)) {
                Column(Modifier.padding(horizontal=3.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(index==0)ClassificationIcon("cube",Color(0xFF359EFF),Modifier.size(23.dp)) else Text(if(index==1)"↑" else if(index==2)"+" else "!",color=tint,fontSize=23.sp,fontWeight=FontWeight.Bold)
                    Text(number(n),fontSize=21.sp,fontWeight=FontWeight.Bold)
                    Text(label,fontSize=10.sp,maxLines=1)
                }
            }
        }
    }
    InventoryCard(tint=Color(0xFF008DFF)) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("ⓘ",color=Color(0xFF009BFF),fontSize=27.sp)
            Text("Se actualizarán las existencias y se agregarán los productos nuevos. Los productos con incidencias críticas quedarán excluidos.",fontSize=12.sp,lineHeight=17.sp)
        }
    }
    InventoryCard(tint=Color(0xFF008DFF)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Sincronización con Firebase y Render",fontSize=15.sp,lineHeight=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            Text("${(fraction.coerceIn(0f,1f)*100).toInt()}%",fontSize=19.sp,fontWeight=FontWeight.Bold)
        }
        LinearProgressIndicator(progress={fraction.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(vertical=5.dp).height(8.dp),color=Green,trackColor=Color(0xFF123C65))
        listOf("Preparando datos","Enviando productos","Actualizando existencias","Verificando resultados").forEachIndexed { index,label ->
            val done = index == 0
            val active = syncing && index > 0
            HorizontalDivider(color=Border.copy(alpha=.4f))
            Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(done)Surface(color=Green,shape=CircleShape){Box(Modifier.size(22.dp),contentAlignment=Alignment.Center){ImportGlyph("✓",Navy,Modifier.size(14.dp))}}
                else if(active)CircularProgressIndicator(modifier=Modifier.size(22.dp),color=Color(0xFF009BFF),trackColor=Border,strokeWidth=3.dp)
                else Box(Modifier.size(22.dp).border(2.dp,Muted,CircleShape))
                Text(label,modifier=Modifier.weight(1f),fontSize=12.sp)
                Text(if(done)"Completado" else if(active)"En progreso" else "Pendiente",fontSize=11.sp,color=if(done)Green else if(active)Amber else Muted)
            }
        }
        Text(if(syncing) "${job.optInt("cursor")} de $total productos revisados. Las escrituras y sus resultados se confirman por producto." else "La actualización comenzará después de tu confirmación.",fontSize=11.sp,lineHeight=15.sp,color=Muted)
    }
    InventoryCard {
        Text("Clasificación",fontWeight=FontWeight.Bold,fontSize=14.sp)
        Text("${number((valid-counts.optInt("classificationPending")).coerceAtLeast(0))} completos · ${number(counts.optInt("classificationPending"))} pendientes",color=Muted,fontSize=12.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick=onProducts,contentPadding=PaddingValues(0.dp)){Text("Ver cambios",fontSize=11.sp)}
            TextButton(onClick=onClassification,contentPadding=PaddingValues(0.dp)){Text("Clasificación",fontSize=11.sp)}
            TextButton(onClick=onIssues,contentPadding=PaddingValues(0.dp)){Text("Omitidos",fontSize=11.sp)}
        }
    }
}

@Composable internal fun InventorySyncFooter(enabled: Boolean, syncing: Boolean, onUpdate: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Button(onClick=onUpdate,enabled=enabled&&!syncing,modifier=Modifier.fillMaxWidth().height(47.dp).testTag("sync-update-stock"),shape=RoundedCornerShape(10.dp),colors=ButtonDefaults.buttonColors(containerColor=Blue,contentColor=Color.White)) {
            Text(if(syncing)"Actualizando stock…" else "⟳  Actualizar stock",fontSize=17.sp,fontWeight=FontWeight.Bold)
        }
        OutlinedButton(onClick=onCancel,modifier=Modifier.fillMaxWidth().height(41.dp).testTag("sync-cancel"),shape=RoundedCornerShape(10.dp),border=BorderStroke(1.dp,Muted)) {Text(if(syncing)"Cerrar seguimiento" else "Cancelar",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.SemiBold)}
    }
}

@Composable internal fun InventorySyncConfirmation(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest=onCancel,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp).testTag("sync-confirmation"),shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,Color(0xFF6CC9FF)),color=Color(0xFF001A38),contentColor=Color.White) {
            Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF06366C),Color(0xFF00132C)))).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(13.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    Surface(modifier=Modifier.align(Alignment.Center).size(58.dp),shape=RoundedCornerShape(15.dp),color=Blue) {Box(contentAlignment=Alignment.Center){Text("⟳",fontSize=43.sp,color=Color.White)}}
                    IconButton(onClick=onCancel,modifier=Modifier.align(Alignment.TopEnd).size(26.dp).testTag("sync-confirm-close")){Text("×",fontSize=28.sp,color=Color.White)}
                }
                Text("¿Estás seguro de actualizar el stock?",fontSize=21.sp,lineHeight=25.sp,fontWeight=FontWeight.Bold,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                Text("Se actualizarán las existencias de los productos válidos y se agregarán los productos nuevos. Los productos con incidencias críticas no se sincronizarán.",fontSize=14.sp,lineHeight=19.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                Text("Las series ausentes de productos válidos pasarán a cero. Los productos fuera del Excel permanecerán intactos.",fontSize=11.sp,lineHeight=15.sp,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick=onCancel,modifier=Modifier.weight(1f).heightIn(min=45.dp).testTag("sync-confirm-cancel"),shape=RoundedCornerShape(10.dp),border=BorderStroke(1.dp,Muted)){Text("Cancelar",color=Color.White,fontSize=14.sp)}
                    Button(onClick=onConfirm,modifier=Modifier.weight(1.35f).heightIn(min=45.dp).testTag("sync-confirm-update"),shape=RoundedCornerShape(10.dp),colors=ButtonDefaults.buttonColors(containerColor=Blue)){Text("⟳ Actualizar stock",fontSize=14.sp,fontWeight=FontWeight.Bold)}
                }
            }
        }
    }
}

@Composable
internal fun InventoryCompleted(job: JSONObject, onIssues: () -> Unit, onProducts: () -> Unit, onHome: () -> Unit, onExport: (() -> Unit)? = null, exporting: Boolean = false, exportMessage: String? = null) {
    val counts = job.optJSONObject("summary") ?: JSONObject()
    val partial = job.optString("status") == "PARTIAL" || counts.optInt("syncErrors") > 0
    val failed = job.optJSONObject("history")?.optString("estado") == "FALLIDO"
    val observations = job.optJSONObject("history")?.optString("estado") == "COMPLETADO_CON_OBSERVACIONES"
    val accent = if (failed) Red else if (partial || observations) Amber else Green
    InventoryCard(tint=accent) {
        Column(Modifier.fillMaxWidth().padding(vertical=2.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Surface(color=accent,shape=CircleShape) { Box(Modifier.size(49.dp),contentAlignment=Alignment.Center) { ImportGlyph(if(partial) "!" else "✓",Color.White,Modifier.size(30.dp)) } }
            Text(if(failed) "Importación fallida" else if(partial) "Finalizada con errores de sincronización" else if(observations) "Importación finalizada con observaciones" else "Importación finalizada",fontSize=18.sp,lineHeight=22.sp,fontWeight=FontWeight.Bold,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            Text(if(failed) "No se pudo actualizar el inventario. Revisa los errores de sincronización." else if(partial) "Algunos productos no pudieron actualizarse. Revisa los resultados." else if(observations) "El inventario válido se actualizó. Revisa las incidencias y la clasificación pendiente." else "El inventario válido se actualizó correctamente en Firebase.",fontSize=12.sp,lineHeight=16.sp,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    Text("Resumen de actualización",fontSize=15.sp,fontWeight=FontWeight.Bold)
    val stats=listOf(
        Stat((counts.optInt("productsCreated")+counts.optInt("productsUpdated")+counts.optInt("productsUnchanged")).toString(),"Productos procesados",Green,"cube"),
        Stat(counts.optInt("productsCreated").toString(),"Productos nuevos",Blue,"cube"),
        Stat(counts.optInt("seriesUpdated").toString(),"Series actualizadas",Purple,"series"),
        Stat(counts.optInt("seriesZeroed").toString(),"Series en cero",Amber,"cube"),
        Stat(counts.optInt("productsOmitted").toString(),"Incidencias omitidas",Red,"!"),
        Stat(counts.optInt("syncErrors").toString(),"Errores de sincronización",Color(0xFF7EA9D2),"shield"))
    stats.chunked(2).forEachIndexed { row, pair ->
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            pair.forEachIndexed { col,stat ->
                val index=row*2+col
                InventoryCard(Modifier.weight(1f).then(if(index==4) Modifier.clickable(onClickLabel="Ver incidencias",onClick=onIssues) else if(index==5 && partial) Modifier.clickable(onClickLabel="Ver resultados de productos",onClick=onProducts) else Modifier),stat.tint.copy(alpha=.85f)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Surface(color=stat.tint,shape=RoundedCornerShape(6.dp)) {
                            Box(Modifier.size(33.dp),contentAlignment=Alignment.Center) {
                                if(stat.icon=="cube")Box(Modifier.size(25.dp)) {
                                    ClassificationIcon("cube",Color.White,Modifier.size(23.dp))
                                    if(index==1 || index==3)Text(if(index==1) "+" else "×",fontSize=13.sp,lineHeight=13.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.align(Alignment.BottomEnd).background(stat.tint))
                                }
                                else if(stat.icon=="series")Canvas(Modifier.size(24.dp)) {
                                    val w=size.width;val h=size.height
                                    listOf(.23f,.48f,.73f).forEach { y -> drawPath(Path().apply {moveTo(w*.09f,h*y);lineTo(w*.5f,h*(y-.17f));lineTo(w*.91f,h*y);lineTo(w*.5f,h*(y+.17f));close()},Color.White,style=Stroke(1.3.dp.toPx())) }
                                }
                                else if(stat.icon=="!")Canvas(Modifier.size(25.dp)) {
                                    val w=size.width;val h=size.height
                                    drawPath(Path().apply{moveTo(w*.5f,h*.06f);lineTo(w*.94f,h*.91f);lineTo(w*.06f,h*.91f);close()},Color.White,style=Stroke(1.5.dp.toPx()))
                                    drawLine(Color.White,Offset(w*.5f,h*.32f),Offset(w*.5f,h*.61f),2.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round);drawCircle(Color.White,1.dp.toPx(),Offset(w*.5f,h*.76f))
                                }
                                else if(stat.icon=="shield")Canvas(Modifier.size(23.dp)) {
                                    val w=size.width;val h=size.height
                                    drawPath(Path().apply{moveTo(w*.5f,h*.04f);lineTo(w*.91f,h*.2f);lineTo(w*.84f,h*.65f);quadraticTo(w*.75f,h*.87f,w*.5f,h*.97f);quadraticTo(w*.25f,h*.87f,w*.16f,h*.65f);lineTo(w*.09f,h*.2f);close()},Color.White,style=Stroke(1.7.dp.toPx()))
                                    drawLine(Color.White,Offset(w*.3f,h*.49f),Offset(w*.45f,h*.65f),1.6.dp.toPx());drawLine(Color.White,Offset(w*.45f,h*.65f),Offset(w*.72f,h*.34f),1.6.dp.toPx())
                                } else ImportGlyph(stat.icon,Color.White,Modifier.size(23.dp))
                            }
                        }
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                            Text(java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(stat.value.toLong()),fontSize=22.sp,lineHeight=24.sp,fontWeight=FontWeight.Bold)
                            Text(stat.label,fontSize=10.sp,lineHeight=13.sp)
                        }
                    }
                }
            }
        }
    }
    InventoryCard {
        Text("Información de la importación",fontSize=14.sp,fontWeight=FontWeight.Bold)
        val history = job.optJSONObject("history")
        val recordedDate = history?.optString("fecha")?.let { value -> runCatching {
            java.text.SimpleDateFormat("dd/MM/yyyy · HH:mm", java.util.Locale("es", "PE")).format(java.util.Date.from(java.time.Instant.parse(value)))
        }.getOrNull() }
        val information = listOf(history?.optString("archivo"), recordedDate, history?.optString("usuario"), history?.optString("destino"), history?.optString("idImportacion"))
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        listOf("Archivo:","Fecha:","Usuario:","Destino:","ID de importación:").forEachIndexed { index,label ->
            HorizontalDivider(color=Muted.copy(alpha=.22f))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(listOf("▤","◷","♙","▱","#")[index],fontSize=15.sp,color=Muted,modifier=Modifier.width(17.dp))
                Text(label,fontSize=11.sp,lineHeight=14.sp,modifier=Modifier.weight(1f))
                Text(information[index]?.takeIf { it.isNotBlank() && it != "null" } ?: "—",fontSize=11.sp,lineHeight=14.sp,color=Muted,modifier=Modifier.weight(1.2f).testTag("completed-info-$index"))
            }
        }
    }
    }
    Text("Acciones",fontSize=15.sp,fontWeight=FontWeight.Bold)
    Button(onClick={onExport?.invoke()},enabled=onExport!=null&&!exporting,modifier=Modifier.fillMaxWidth().height(48.dp).testTag("completed-export"),shape=RoundedCornerShape(9.dp),border=BorderStroke(1.dp,Green),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF008344),contentColor=Color.White,disabledContainerColor=Color(0xFF005A3C),disabledContentColor=Muted)) {
        ImportExcelSymbol(Modifier.size(31.dp));Spacer(Modifier.width(12.dp));VerticalDivider(Modifier.height(25.dp),color=Green.copy(alpha=.4f));Text(if(exporting) "Generando resumen…" else "Exportar resumen",modifier=Modifier.weight(1f),fontSize=16.sp,fontWeight=FontWeight.SemiBold,textAlign=androidx.compose.ui.text.style.TextAlign.Center);if(exporting)CircularProgressIndicator(modifier=Modifier.size(23.dp),color=Color.White,strokeWidth=2.dp) else Text("↓",fontSize=26.sp)
    }
    exportMessage?.let { Text(it,fontSize=12.sp,lineHeight=16.sp,color=Muted,modifier=Modifier.testTag("completed-export-message")) }
    OutlinedButton(onClick=onHome,modifier=Modifier.fillMaxWidth().height(43.dp).testTag("completed-home"),shape=RoundedCornerShape(9.dp),border=BorderStroke(1.dp,Muted)) {Text("⌂  Volver al inicio",color=Color.White,fontSize=15.sp,fontWeight=FontWeight.SemiBold)}
}

@Composable
internal fun InventoryDetails(job: JSONObject, rows: List<JSONObject>, incidents: Boolean, showChanges: Boolean = false) {
    var search by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    val all = job.optJSONArray("allIssues")
    val issues = if (all != null) (0 until all.length()).mapNotNull { all.optJSONObject(it) } else rows.flatMap { row ->
        val values = row.optJSONArray("issues")
        if (values == null) emptyList() else (0 until values.length()).mapNotNull { values.optJSONObject(it) }
    }
    fun warning(row: JSONObject) = row.optString("severity").uppercase() in setOf("WARNING", "ADVERTENCIA")
    fun pending(row: JSONObject) = (row.optJSONObject("plan")?.optJSONObject("classification")?.optJSONArray("pending")?.length() ?: 0) > 0
    fun excluded(row: JSONObject) = row.optJSONObject("plan")?.optBoolean("excluded") == true
    val tabs = if (incidents) listOf("Todas (${job.optInt("issueCount")})", "Críticas (${issues.count { !warning(it) }})", "Advertencias (${issues.count { warning(it) }})") else listOf("Pendientes", "Completos", "Apartados")
    if (!showChanges) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tabs.forEachIndexed { index, label ->
        OutlinedButton(onClick = { tab = index }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (tab == index) Blue else Color.Transparent), border = BorderStroke(1.dp, Border)) { Text(label, fontSize = 11.sp, color = Color.White) }
    } }
    OutlinedTextField(search, { search = it }, placeholder = { Text(if (incidents) "Buscar por código, nombre o motivo…" else "Buscar producto…", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp))
    val visible = (if (incidents) issues.filter { tab == 0 || (tab == 2) == warning(it) } else rows.filter { showChanges || when (tab) { 0 -> pending(it) && !excluded(it); 1 -> !pending(it) && !excluded(it); else -> excluded(it) } }).filter { search.isBlank() || it.toString().contains(search, ignoreCase = true) }
    visible.forEach { row ->
        val tint = if (incidents && !warning(row)) Red else Amber
        InventoryCard(Modifier.clickable { selected = row }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Badge(if (incidents) "!" else "▧", if (incidents) tint else Color(0xFF6785A8))
                Column(Modifier.weight(1f)) {
                    Text(if (incidents) "Fila ${row.optInt("row")}   ${row.optString("sheet")}" else row.optString("name"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (incidents) issueTitle(row.optString("type")) else "Código: ${row.optString("code")}", fontSize = 12.sp)
                    if (incidents) Text(row.optString("serial").takeIf { it.isNotBlank() && it != "null" }?.let { "Serie: $it" } ?: row.optString("description"), fontSize = 11.sp, color = Muted, maxLines = 2)
                }
                Surface(color = tint, shape = RoundedCornerShape(5.dp)) { Text(if (incidents) { if (warning(row)) "Advertencia" else "Crítica" } else if (pending(row)) "Faltan datos" else if (excluded(row)) "Apartado" else "Completo", fontSize = 10.sp, color = if (tint == Red) Color.White else Navy, modifier = Modifier.padding(5.dp)) }
                Text("›")
            }
        }
    }
    if (visible.isEmpty()) Text("Sin coincidencias en los registros cargados.", fontSize = 13.sp, color = Muted)
    selected?.let { row -> InventoryImportFrame(if (incidents) "Detalle de incidencia" else if (showChanges) "Detalle de cambios" else "Clasificar producto", { selected = null }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (incidents) {
                InventoryCard(tint = if (warning(row)) Amber else Red) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Badge("!", if (warning(row)) Amber else Red); Column { Text(issueTitle(row.optString("type")), fontWeight = FontWeight.Bold); Text(row.optString("description"), fontSize = 12.sp) } }
                }
                InventoryCard {
                    Text("Ubicación en el archivo", fontWeight = FontWeight.SemiBold)
                    listOf("Hoja" to "sheet", "Fila" to "row", "Código" to "productCode", "Producto" to "productName", "Serie" to "serial").forEach { (label, key) -> Text("$label: ${row.optString(key).takeIf { it != "null" && it.isNotBlank() } ?: "—"}", fontSize = 13.sp) }
                }
                Text("●  Acción requerida", color = Amber, fontWeight = FontWeight.Bold)
                Text(row.optString("reason").takeIf { it.isNotBlank() } ?: "Corrige el archivo Excel y vuelve a importarlo.", fontSize = 13.sp)
                ImportButton("←  Ver en lista", action = { selected = null })
            } else {
                val classification = row.optJSONObject("plan")?.optJSONObject("classification")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = Color(0xFFF1F5FF), shape = RoundedCornerShape(9.dp)) { Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) { ImportGlyph("♙", Border, Modifier.size(34.dp)) } }
                    Column { Text(row.optString("name"), fontWeight = FontWeight.Bold, fontSize = 15.sp); Text("Código: ${row.optString("code")}", color = Muted, fontSize = 12.sp) }
                }
                listOf("Categoría" to classification?.optString("categoryName"), "Marca" to classification?.optString("brandName"), "Descripción" to classification?.optJSONObject("fields")?.optString("descripcion")).forEach { (label, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, fontSize = 12.sp)
                            InventoryCard { Text(value?.takeIf { it != "null" && it.isNotBlank() } ?: "Pendiente", fontSize = 13.sp, lineHeight = 18.sp) }
                        }
                        if (classification?.optString("source")?.contains("gemini", ignoreCase = true) == true && !value.isNullOrBlank() && value != "null") {
                            Surface(color = Green.copy(alpha = .12f), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Green.copy(alpha = .4f))) { Text("Sugerido por IA", color = Green, fontSize = 10.sp, modifier = Modifier.padding(7.dp)) }
                        }
                    }
                }
                Text("Fuente de clasificación", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                SummaryLine(classification?.optString("source")?.takeIf { it.isNotBlank() && it != "null" } ?: "Sin clasificación disponible", "")
                if (showChanges) {
                    InventoryCard {
                        Text("Existencias", fontWeight = FontWeight.SemiBold)
                        Text("${row.optInt("series")} series · Stock ${row.optLong("stock")}", fontSize = 13.sp)
                        var limit by remember { mutableIntStateOf(20) }
                        val operations = row.optJSONObject("plan")?.optJSONArray("operations")
                        if (operations != null) {
                            (0 until minOf(limit, operations.length())).forEach { index -> operations.optJSONObject(index)?.let { op ->
                                val label = when (op.optString("type")) { "CREATE_PRODUCT" -> "Crear producto"; "UPDATE_PRODUCT" -> "Actualizar producto"; "CREATE_EXISTENCE" -> "Crear serie"; "UPDATE_EXISTENCE" -> "Actualizar serie"; "ZERO_EXISTENCE" -> "Serie a cero"; else -> op.optString("type") }
                                Text(label + (op.optString("serial").takeIf { it.isNotBlank() && it != "null" }?.let { ": $it" } ?: "") + (if (op.has("quantity") && !op.isNull("quantity")) " · Cantidad final: ${op.optLong("quantity")}" else ""), fontSize = 12.sp)
                            } }
                            if (operations.length() > limit) TextButton(onClick = { limit += 20 }) { Text("Ver más cambios") }
                        }
                        row.optJSONObject("receipt")?.let { Text("Resultado: ${it.optString("status")}", fontSize = 12.sp) }
                    }
                }
                if (showChanges) ImportButton("←  Ver en lista", action = { selected = null }) else {
                    Text("La edición de esta propuesta aún no está disponible.", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selected = null }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text("Descartar", color = Color.White) }
                        Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Aplicar") }
                    }
                }
            }
        }
    } }
}

private fun issueTitle(type: String) = when (type) {
    "DATABASE_CONFLICT" -> "Conflicto con Firebase"; "PRODUCT_LIMIT" -> "Producto apartado por tamaño"
    "DUPLICATE_SERIAL" -> "Serie duplicada"; "DUPLICATE_SKU" -> "Código duplicado"; "INVALID_QUANTITY", "INVALID_TOTAL" -> "Cantidad inválida"; "IDENTITY" -> "Identidad incompleta"
    "SUMMARY_MISMATCH", "COUNT_MISMATCH" -> "Diferencia en resumen"; "NAME_MISMATCH" -> "Producto con nombre distinto"; "INVALID_SERIAL" -> "Serie inválida"; else -> "Incidencia de inventario"
}
