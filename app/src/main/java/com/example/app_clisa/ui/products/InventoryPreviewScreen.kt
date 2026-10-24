package com.example.app_clisa.ui.products

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.app_clisa.data.excel.*
import com.example.app_clisa.ui.registration.RegisterIcon
import com.example.app_clisa.ui.registration.RegistrationBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val PreviewWhite = Color(0xFFF1F5FF)
private val PreviewMuted = Color(0xFFA9C8EC)
private val PreviewBorder = Color(0xFF07568B)
private val PreviewBlue = Color(0xFF0061FF)
private val PreviewGreen = Color(0xFF00E6A0)

/** All inputs are local immutable results. This screen has no Firebase/repository dependency. */
@Composable
internal fun InventoryPreviewScreen(model: InventoryPreview, comparison: InventoryComparison, excel: ExcelInventory,
    fileName: String, onClose: () -> Unit, onReadSummary: () -> Unit, safetyContent: @Composable () -> Unit = {}) {
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedName?.let { PreviewGroup.valueOf(it) }
    var query by rememberSaveable(selectedName) { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var tab by rememberSaveable { mutableStateOf("Todos") }
    var showWarnings by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val conflictFacts = remember(model) { model.groups.getValue(PreviewGroup.CONFLICTS).flatMap { it.facts } }
    val conflictIds = remember(conflictFacts) { conflictFacts.mapNotNull { it.productId }.toSet() }
    val conflictCodes = remember(conflictFacts) { conflictFacts.mapNotNull { it.productCode }.toSet() }
    val overviewScroll = rememberLazyListState()
    fun back() { if (selected != null) selectedName = null else onClose() }
    val rows by produceState<List<PreviewDetail>>(emptyList(), selected, query, model) {
        if (query.isNotBlank()) delay(200)
        value = withContext(Dispatchers.Default) { model.groups[selected].orEmpty().filter { it.matches(query) } }
    }
    val products by produceState<List<PreviewProduct>>(model.products, query, model, tab) {
        if (query.isNotBlank()) delay(200)
        value = withContext(Dispatchers.Default) {
            val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            model.products.filter { p ->
                val text = "${p.name} ${p.code.orEmpty()} ${p.documentId.orEmpty()}"
                val inTab = when (tab) {
                    "Nuevos" -> p.group == PreviewGroup.NEW_PRODUCTS || p.details.any { it.group == PreviewGroup.NEW_EXISTENCES }
                    "Diferentes" -> p.details.any { it.group in listOf(PreviewGroup.DIFFERENT_QUANTITIES, PreviewGroup.ABSENT_EXISTENCES, PreviewGroup.STOCK) }
                    else -> true
                }
                inTab && words.all { word -> text.contains(word, true) || p.details.any { it.serial?.contains(word, true) == true } }
            }
        }
    }
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MaterialTheme(colorScheme = darkColorScheme(primary = PreviewBlue, secondary = PreviewGreen,
            background = Color(0xFF000B1A), surface = Color(0xFF03152C), onSurface = PreviewWhite, onBackground = PreviewWhite)) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF000B1A)) {
                Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF00132F), Color(0xFF000915))))
                    .statusBarsPadding().navigationBarsPadding().imePadding()) {
                    Box(Modifier.fillMaxWidth()) {
                        RegistrationBanner(Modifier.matchParentSize(), showScanner = false)
                        Column(Modifier.padding(bottom = 12.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { back() }, modifier = Modifier.testTag("preview-back")) { RegisterIcon("back", PreviewWhite, "Volver") }
                                Text(if (selected == null) "Revisar sincronización" else selected.label,
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f).padding(end = 12.dp))
                            }
                            Text("Analiza las diferencias antes de actualizar", color = PreviewMuted,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp, end = 14.dp))
                        }
                    }
                    if (selected == null) {
                        LazyColumn(state = overviewScroll, modifier = Modifier.weight(1f).testTag("preview-summary"),
                            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(PreviewGroup.NEW_PRODUCTS, PreviewGroup.NEW_EXISTENCES, PreviewGroup.DIFFERENT_QUANTITIES,
                                        PreviewGroup.ABSENT_EXISTENCES, PreviewGroup.UNCHANGED, PreviewGroup.CONFLICTS).chunked(2).forEach { pair ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                            pair.forEach { group ->
                                                PreviewCountCard(group, model.groups.getValue(group).size, Modifier.weight(1f).fillMaxHeight()) { selectedName = group.name }
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("Todos", "Nuevos", "Diferentes").forEach { label ->
                                        val count = model.products.count { p -> when (label) {
                                            "Nuevos" -> p.group == PreviewGroup.NEW_PRODUCTS || p.details.any { it.group == PreviewGroup.NEW_EXISTENCES }
                                            "Diferentes" -> p.details.any { it.group in listOf(PreviewGroup.DIFFERENT_QUANTITIES, PreviewGroup.ABSENT_EXISTENCES, PreviewGroup.STOCK) }
                                            else -> true
                                        } }
                                        FilterChip(selected = tab == label, onClick = { tab = label }, modifier = Modifier.testTag("preview-tab-$label"),
                                            label = { Text("$label ($count)", fontSize = 11.sp) }, shape = RoundedCornerShape(8.dp),
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PreviewBlue, selectedLabelColor = PreviewWhite))
                                        }
                                }
                            }
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.weight(1f)) { PreviewSearch(query) { query = it } }
                                    Box {
                                        OutlinedIconButton(onClick = { showFilters = true }, modifier = Modifier.testTag("preview-more-groups"), shape = RoundedCornerShape(10.dp)) {
                                            Canvas(Modifier.size(22.dp)) {
                                                val s = size.width / 24f
                                                drawPath(Path().apply { moveTo(3*s, 4*s); lineTo(21*s, 4*s); lineTo(14*s, 12*s)
                                                    lineTo(14*s, 20*s); lineTo(10*s, 18*s); lineTo(10*s, 12*s); close() }, PreviewMuted, style = Stroke(1.6f*s))
                                            }
                                        }
                                        DropdownMenu(showFilters, { showFilters = false }) {
                                            PreviewGroup.entries.forEach { group -> DropdownMenuItem(text = { Text("${group.label} (${model.groups.getValue(group).size})") },
                                                onClick = { showFilters = false; selectedName = group.name }) }
                                        }
                                    }
                                }
                            }
                            item {
                                Text("Solo revisión · ausencias conservadas${if (excel.errorCount > 0) " · ${excel.errorCount} incidencias" else ""} · Ver avisos",
                                    color = Color(0xFFFFBC39), fontSize = 10.sp, modifier = Modifier.fillMaxWidth().clickable { showWarnings = !showWarnings }.padding(vertical = 3.dp))
                                if (showWarnings) PreviewWarnings(excel, comparison)
                            }
                            item { safetyContent() }
                            items(products, key = { it.id }) { p ->
                                val open = expanded[p.id] ?: (p.id == products.firstOrNull()?.id)
                                PreviewProductCard(p, open, p.documentId in conflictIds || p.code in conflictCodes,
                                    { expanded[p.id] = !open }) { group -> selectedName = group.name }
                            }
                            if (products.isEmpty()) item { Text("No hay productos que coincidan. Revisa también los conflictos y las incidencias del Excel.", color = PreviewMuted) }
                            item { EstimateCard(comparison) }
                            item { Text(fileName, color = PreviewMuted, style = MaterialTheme.typography.bodySmall) }
                            item { TextButton(onClick = onReadSummary) { Text("Volver al resumen del Excel") } }
                            item { ReadingInterval(comparison) }
                        }
                    } else {
                        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                            PreviewSearch(query) { query = it }
                            Text("${rows.size} resultados · filtro local", color = PreviewMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                            if (selected == PreviewGroup.ABSENT_EXISTENCES || selected == PreviewGroup.ABSENT_PRODUCTS) {
                                PreviewNotice("Ausencias pendientes", "Los documentos ausentes se conservan sin cambios. No se eliminan ni se asigna cantidad cero, aunque el Excel esté completo.", Color(0xFFFF677B))
                                Spacer(Modifier.height(8.dp))
                            }
                            key(selected) {
                                LazyColumn(Modifier.fillMaxSize().testTag("preview-details"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                                    if (rows.isEmpty()) item { Text("No hay resultados en este grupo para el filtro actual.", color = PreviewMuted) }
                                    items(rows, key = { it.id }) { row -> PreviewDetailCard(row) }
                                }
                            }
                        }
                    }
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("preview-step4"),
                        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF123B6C), disabledContentColor = PreviewMuted)) {
                        Text("Disponible en el paso 4", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportReviewSteps() {
    Box(Modifier.fillMaxWidth()) {
    Canvas(Modifier.fillMaxWidth().height(30.dp)) {
        drawLine(PreviewBorder, Offset(size.width * .1f, size.height / 2), Offset(size.width * .9f, size.height / 2), strokeWidth = 2.dp.toPx())
        drawLine(PreviewGreen, Offset(size.width * .1f, size.height / 2), Offset(size.width * .5f, size.height / 2), strokeWidth = 2.dp.toPx())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("Seleccionar", "Analizar", "Revisar", "Sincronizar", "Clasificar").forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = CircleShape, color = if (index <= 2) PreviewGreen else Color(0xFF001731),
                    border = BorderStroke(if (index == 2) 2.dp else 1.dp, if (index <= 2) PreviewGreen else PreviewBorder)) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = if (index <= 2) Color(0xFF00152D) else PreviewMuted, fontWeight = FontWeight.Bold)
                    }
                }
                Text(label, color = if (index == 2) PreviewWhite else PreviewMuted,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
    }
}

private fun groupColor(group: PreviewGroup) = when (group) {
    PreviewGroup.NEW_PRODUCTS -> PreviewGreen
    PreviewGroup.NEW_EXISTENCES, PreviewGroup.EXISTING_PRODUCTS -> Color(0xFF008DFF)
    PreviewGroup.DIFFERENT_QUANTITIES, PreviewGroup.STOCK -> Color(0xFFFFBC39)
    PreviewGroup.ABSENT_EXISTENCES, PreviewGroup.ABSENT_PRODUCTS -> Color(0xFFFF4E70)
    PreviewGroup.SERIAL_CONFLICTS, PreviewGroup.CONFLICTS -> Color(0xFF9A70FF)
    PreviewGroup.UNCHANGED -> Color(0xFF88A6CD)
}

@Composable
private fun PreviewCountCard(group: PreviewGroup, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val tint = groupColor(group)
    Surface(onClick = onClick, modifier = modifier.testTag("preview-group-${group.name}"), shape = RoundedCornerShape(12.dp), color = Color(0xFF03152C), border = BorderStroke(1.dp, tint.copy(alpha = .7f))) {
        Row(Modifier.background(Brush.horizontalGradient(listOf(tint.copy(alpha = .18f), Color.Transparent))).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(tint.copy(alpha = .25f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                RegisterIcon(if (group == PreviewGroup.CONFLICTS) "barcode" else "product", tint, null, 20.dp)
            }
            Column(Modifier.weight(1f)) {
                Text("$count", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(when(group) { PreviewGroup.UNCHANGED -> "Sin cambios"; PreviewGroup.CONFLICTS -> "Conflictos detectados"; else -> group.label }, color = PreviewMuted, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun PreviewSearch(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(query, onChange, modifier = Modifier.fillMaxWidth().testTag("preview-search"), singleLine = true, shape = RoundedCornerShape(12.dp),
        placeholder = { Text("Buscar código, producto o serie…", style = MaterialTheme.typography.bodySmall) },
        leadingIcon = { RegisterIcon("search", PreviewMuted, null) }, textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = PreviewBorder, focusedBorderColor = PreviewBlue))
}

@Composable
private fun PreviewWarnings(excel: ExcelInventory, comparison: InventoryComparison) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (excel.errorCount > 0 || comparison.sourceHasErrors) PreviewNotice("Excel con incidencias: ${excel.errorCount}",
            "Hay errores o filas no interpretadas. ${excel.uncodedProductCount} grupos sin código. Revisa el resumen del archivo; una ausencia puede ser aparente.", Color(0xFFFFBC39))
        PreviewNotice("Reporte completo sin certificar", "Seleccionar el archivo no acredita su alcance completo. Las ausencias se conservan para revisión. Se comparan series individuales; las filas de resumen no se cuentan como existencias.", PreviewMuted)
        if (comparison.conflicts.isNotEmpty()) PreviewNotice("${comparison.conflicts.size} conflictos pendientes",
            "Los códigos y series ambiguos deben resolverse antes de sincronizar. No se elige ninguna coincidencia automáticamente.", Color(0xFFB293FF))
    }
}

@Composable
private fun PreviewNotice(title: String, message: String, tint: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = .08f), border = BorderStroke(1.dp, tint.copy(alpha = .5f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = tint, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(message, color = PreviewMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EstimateCard(result: InventoryComparison) {
    PreviewNotice("Escrituras estimadas: ${result.estimatedInventoryWrites} · preliminar",
        "Puede cambiar en el paso 4. Excluye documentos sin cambios, ${result.excludedProductsFromEstimate} códigos con conflictos, ausencias conservadas sin escritura, metadatos y cantidadRestante. No es un plan ejecutable.", PreviewMuted)
}

@Composable
private fun PreviewProductCard(p: PreviewProduct, expanded: Boolean, hasConflict: Boolean, onExpand: () -> Unit, onGroup: (PreviewGroup) -> Unit) {
    val different = p.group == PreviewGroup.ABSENT_PRODUCTS || p.details.any { it.group in listOf(PreviewGroup.NEW_EXISTENCES, PreviewGroup.DIFFERENT_QUANTITIES, PreviewGroup.ABSENT_EXISTENCES, PreviewGroup.STOCK) }
    val label = when { hasConflict -> "Conflictos"; p.group == PreviewGroup.NEW_PRODUCTS -> "Nuevo"; different -> "Diferencias"; else -> "Sin cambios" }
    val tint = when { hasConflict -> Color(0xFFB293FF); p.group == PreviewGroup.NEW_PRODUCTS -> PreviewGreen; different -> Color(0xFFFFBC39); else -> PreviewMuted }
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF02162E), border = BorderStroke(1.dp, PreviewBorder)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).testTag("preview-product-${p.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(40.dp).background(Color(0xFF123654), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { RegisterIcon("product", PreviewMuted, null) }
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp)
                    Text("Código: ${p.code ?: "sin código"}", color = PreviewMuted, fontSize = 10.sp)
                    Surface(color = tint, shape = RoundedCornerShape(6.dp)) { Text(label, color = Color(0xFF001329), fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)) }
                }
                Text(if (expanded) "⌃" else "⌄", color = PreviewMuted, fontSize = 20.sp)
            }
            if (expanded) {
                HorizontalDivider(color = PreviewBorder)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Stock actual: ${p.stock ?: "sin dato"}", color = PreviewMuted, fontSize = 10.sp)
                    Text("Stock Excel: ${p.expectedStock ?: "no fiable"}", color = PreviewMuted, fontSize = 10.sp)
                }
                Text("cantidadRestante: ${p.remaining ?: "sin dato"}", color = PreviewMuted, fontSize = 10.sp)
                Text("Números de serie", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                p.details.filter { it.serial != null }.take(5).forEach { row ->
                    Row(Modifier.fillMaxWidth().background(groupColor(row.group).copy(alpha = .14f), RoundedCornerShape(6.dp))
                        .clickable { onGroup(row.group) }.padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RegisterIcon("barcode", PreviewMuted, null, 14.dp)
                        Text(row.serial.orEmpty(), modifier = Modifier.weight(1f).padding(start = 6.dp), fontSize = 11.sp)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(when(row.group) { PreviewGroup.NEW_EXISTENCES -> "Crear"; PreviewGroup.ABSENT_EXISTENCES -> "Conservar";
                                PreviewGroup.UNCHANGED -> "Sin cambios"; else -> "Actualizar" }, color = groupColor(row.group), fontSize = 9.sp)
                            Text(quantityTransition(row), color = PreviewMuted, fontSize = 9.sp)
                        }
                    }
                }
                Text("ID real: ${p.documentId ?: "sin asignar"}", color = PreviewMuted, fontSize = 9.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (hasConflict) Text("Ver conflictos", color = tint, fontSize = 10.sp,
                        modifier = Modifier.clickable { onGroup(PreviewGroup.CONFLICTS) }.padding(vertical = 8.dp))
                    p.details.map { it.group }.distinct().forEach { group ->
                        Text("Ver ${group.label.lowercase()}", color = groupColor(group), fontSize = 10.sp,
                            modifier = Modifier.clickable { onGroup(group) }.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

private fun quantityTransition(row: PreviewDetail) = when (row.group) {
    PreviewGroup.ABSENT_EXISTENCES -> "${row.oldQuantity ?: "?"} → sin definir"
    PreviewGroup.NEW_EXISTENCES -> "sin documento → ${row.excelQuantity ?: "?"}"
    else -> "${row.oldQuantity ?: "?"} → ${row.excelQuantity ?: "?"}"
}

@Composable
private fun PreviewDetailCard(row: PreviewDetail) {
    var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
    var factLimit by rememberSaveable(row.id) { mutableIntStateOf(20) }
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF02162E), border = BorderStroke(1.dp, groupColor(row.group).copy(alpha = .55f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.title, fontWeight = FontWeight.SemiBold)
            row.productCode?.let { Text("Código del producto: $it", color = PreviewMuted, style = MaterialTheme.typography.bodySmall) }
            row.productId?.let { Text("ID real del producto: $it", color = PreviewMuted, style = MaterialTheme.typography.bodySmall) }
            row.serial?.let { Text("Serie: «$it»", fontWeight = FontWeight.Medium) }
            row.existenceId?.let { Text("ID real de existencia: $it", color = PreviewMuted, style = MaterialTheme.typography.bodySmall) }
            if (row.serial != null) Text("Cantidad: ${quantityTransition(row)}", color = groupColor(row.group))
            if (row.group in listOf(PreviewGroup.STOCK, PreviewGroup.EXISTING_PRODUCTS, PreviewGroup.ABSENT_PRODUCTS, PreviewGroup.NEW_PRODUCTS)) StockValues(row.stock, row.remaining, row.expectedStock)
            if (row.note.isNotEmpty()) Text(row.note, color = PreviewMuted, style = MaterialTheme.typography.bodySmall)
            if (row.references.isNotEmpty() || row.facts.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar referencias y series"
                    else if (row.facts.isEmpty()) "Ver referencias (${row.references.size})" else "Ver referencias y series (${row.facts.size})") }
                if (expanded) {
                    row.references.take(factLimit).forEach { Text(it, color = PreviewMuted, style = MaterialTheme.typography.labelSmall) }
                    row.facts.take(factLimit).forEach { f ->
                        HorizontalDivider(color = PreviewBorder)
                        Text("Producto: ${f.productCode ?: "sin código"} · ID: ${f.productId ?: "sin resolver"}", style = MaterialTheme.typography.bodySmall)
                        f.serial?.let { Text("Serie: «$it» · actual: ${f.oldQuantity ?: "sin dato"} · Excel: ${f.excelQuantity ?: "sin dato"}", style = MaterialTheme.typography.bodySmall) }
                        f.existenceId?.let { Text("ID existencia: $it", color = PreviewMuted, style = MaterialTheme.typography.labelSmall) }
                        Text(f.reference, color = PreviewMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (maxOf(row.facts.size, row.references.size) > factLimit) TextButton(onClick = { factLimit += 20 }) { Text("Mostrar hasta 20 referencias y series más") }
                }
            }
        }
    }
}

@Composable
private fun StockValues(stock: Long?, remaining: Long?, expected: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Stock actual: ${stock ?: "sin dato"}", color = PreviewMuted, style = MaterialTheme.typography.bodySmall)
        Text("Stock esperado Excel: ${expected ?: "no fiable o sin correspondencia"}", color = PreviewMuted, style = MaterialTheme.typography.bodySmall)
        Text("cantidadRestante: ${remaining ?: "sin dato"}", color = PreviewMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReadingInterval(result: InventoryComparison) {
    Text("Lectura del ${DateFormat.getDateTimeInstance().format(Date(result.startedAtMillis))} al ${DateFormat.getDateTimeInstance().format(Date(result.finishedAtMillis))}. No es una instantánea atómica; los datos deberán revalidarse antes de una futura sincronización. Esta vista no realiza consultas ni modifica el inventario.",
        color = PreviewMuted, style = MaterialTheme.typography.bodySmall)
}
