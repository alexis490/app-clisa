package com.example.app_clisa.ui.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app_clisa.data.*
import com.example.app_clisa.ui.registration.RegisterIcon
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private val White = Color(0xFFF1F5FF)
private val Muted = Color(0xFFABC7EC)
private val Blue = Color(0xFF0061FF)
private val Border = Color(0xFF07568B)
private val LightBlue = Color(0xFF6FC7FF)

@Composable
internal fun ReportCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color.Transparent, border = BorderStroke(1.dp, Border)) {
        Column(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF031C34), Color(0xFF02152B)))), content = content)
    }
}
@Composable
internal fun ReportMetric(label: String, number: String, caption: String, icon: String, accent: Color, modifier: Modifier) {
    Surface(modifier.heightIn(min = 104.dp), shape = RoundedCornerShape(12.dp), color = Color.Transparent, border = BorderStroke(1.dp, Border)) {
        Column(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF062B61), Color(0xFF02152B)))).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(37.dp), shape = RoundedCornerShape(10.dp), color = accent) {
                    Box(contentAlignment = Alignment.Center) {
                        if (icon in listOf("product", "payment")) RegisterIcon(icon, White, null, 25.dp) else ReportSymbol(icon, White, null)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                    Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(number, color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(caption, color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.End).padding(top = 8.dp))
        }
    }
}
@Composable
internal fun ReportSelect(placeholder: String, icon: String, value: String, options: List<Pair<String, String>>, modifier: Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(onClick = { expanded = true }, shape = RoundedCornerShape(10.dp), color = Color(0xFF032143), border = BorderStroke(1.dp, Border)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RegisterIcon(icon, White, null, 21.dp)
                Text(options.find { it.first == value }?.second ?: placeholder, color = White, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                RegisterIcon("down", Muted, null, 13.dp)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
            DropdownMenuItem(text = { Text(placeholder) }, onClick = { expanded = false; onSelect("") })
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}
@Composable
internal fun ReportCardTitle(icon: String, title: String, all: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RegisterIcon(icon, Color(0xFF009EFF), null, 25.dp)
        Text(title, color = White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 10.dp))
        TextButton(onClick = onToggle) { Text(if (all) "Ver menos" else "Ver todos", color = Color(0xFF009EFF), fontSize = 12.sp) }
    }
}
@Composable
internal fun ReportTableHeader(values: List<String>, weights: List<Float>) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF052647)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        values.forEachIndexed { index, value -> Text(value, color = Muted, fontSize = 10.sp, modifier = Modifier.weight(weights[index])) }
    }
}
@Composable
internal fun ReportTableRow(values: List<String>, weights: List<Float>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { index, value -> Text(value, color = White, fontSize = 11.sp, modifier = Modifier.weight(weights[index]).padding(end = 4.dp)) }
    }
    HorizontalDivider(color = Border, thickness = .5.dp)
}
@Composable
internal fun ChartEmpty() {
    Text("Sin movimientos en los datos seleccionados.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
}
private data class ChartGroup(val label: String, val outgoing: Int, val incoming: Int = 0)

@Composable
internal fun DateChart(buckets: List<ReportBucket>, interval: ReportInterval) {
    if (buckets.none { it.count > 0 }) { ChartEmpty(); return }
    val formatter = DateTimeFormatter.ofPattern(when (interval) { ReportInterval.DAY -> "dd MMM"; ReportInterval.WEEK -> "dd/MM"; ReportInterval.MONTH -> "MMM yy" }, Locale.forLanguageTag("es-PE"))
    Bars(buckets.takeLast(7).map { ChartGroup(it.date.format(formatter), it.count) }, false)
    if (buckets.size > 7) Text("Últimos 7 intervalos del período seleccionado.", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(10.dp))
}
@Composable
internal fun StoreChart(stores: List<StoreMovements>) {
    if (stores.isEmpty()) { ChartEmpty(); return }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(Blue, RoundedCornerShape(2.dp))); Text(" Salidas   ", color = Muted, fontSize = 10.sp)
        Box(Modifier.size(9.dp).background(LightBlue, RoundedCornerShape(2.dp))); Text(" Entradas", color = Muted, fontSize = 10.sp)
    }
    Bars(stores.take(7).map { ChartGroup(it.name, it.outgoing, it.incoming) }, true)
    if (stores.size > 7) Text("Se muestran las primeras 7 tiendas.", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(10.dp))
}
@Composable
private fun Bars(groups: List<ChartGroup>, dual: Boolean) {
    val maximum = groups.maxOfOrNull { maxOf(it.outgoing, it.incoming) } ?: 0
    val step = ceil(maximum.coerceAtLeast(1) / 4.0).toInt().coerceAtLeast(1)
    val scale = step * 4f
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 8.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(150.dp)) {
            Column(Modifier.width(28.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                (4 downTo 0).forEach { Text((it * step).toString(), color = Muted, fontSize = 9.sp) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                for (row in 0..4) {
                    val y = size.height * row / 4f
                    drawLine(Border, Offset(0f, y), Offset(size.width, y), 1f)
                }
                val segment = size.width / groups.size.coerceAtLeast(1)
                groups.forEachIndexed { index, group ->
                    val width = segment * if (dual) .29f else .62f
                    val x = segment * index + segment * if (dual) .16f else .19f
                    val height = size.height * group.outgoing / scale
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF0088FF), Blue)), Offset(x, size.height - height), Size(width, height))
                    if (dual) {
                        val incoming = size.height * group.incoming / scale
                        drawRect(LightBlue, Offset(x + width + segment * .04f, size.height - incoming), Size(width, incoming))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 6.dp)) {
            groups.forEach { Text(it.label, color = Muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)) }
        }
    }
}

@Composable
internal fun ReportSymbol(kind: String, color: Color, description: String?) {
    Canvas(Modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        val s = size.width / 24f
        val stroke = Stroke(1.8f * s)
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(color, Offset(a*s,b*s), Offset(c*s,d*s), stroke.width, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, Offset(x*s,y*s), Size(w*s,h*s), CornerRadius(1.5f*s), style = stroke)
        when (kind) {
            "document" -> { rect(4f,2f,16f,20f); line(8f,8f,16f,8f); line(8f,12f,16f,12f); line(8f,16f,14f,16f) }
            "filter" -> drawPath(Path().apply { moveTo(2*s,3*s); lineTo(22*s,3*s); lineTo(14*s,12*s); lineTo(14*s,21*s); lineTo(10*s,18*s); lineTo(10*s,12*s); close() },color,style=stroke)
            "clock" -> { drawCircle(color,10*s,Offset(12*s,12*s),style=stroke); line(12f,5f,12f,12f); line(12f,12f,17f,15f) }
            "exit" -> { rect(3f,3f,13f,18f); line(10f,12f,23f,12f); line(18f,7f,23f,12f); line(18f,17f,23f,12f) }
        }
    }
}
