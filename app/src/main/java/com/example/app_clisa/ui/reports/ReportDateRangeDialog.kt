package com.example.app_clisa.ui.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.app_clisa.ui.registration.RegisterIcon
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CalendarNavy = Color(0xFF031329)
private val CalendarBlue = Color(0xFF0061FF)
private val CalendarCyan = Color(0xFF00AAFF)
private val CalendarWhite = Color(0xFFF1F5FF)
private val CalendarMuted = Color(0xFFABC7EC)
private val CalendarBorder = Color(0xFF07568B)
private val CalendarLocale = Locale.forLanguageTag("es-PE")

@Composable
internal fun ReportDateRangeDialog(start: LocalDate, end: LocalDate, onDismiss: () -> Unit,
    onApply: (LocalDate, LocalDate) -> Unit) {
    var startValue by rememberSaveable { mutableStateOf(start.toString()) }
    var endValue by rememberSaveable { mutableStateOf(end.toString()) }
    var selectingEnd by rememberSaveable { mutableStateOf(false) }
    var monthValue by rememberSaveable { mutableStateOf(YearMonth.from(start).toString()) }
    val selection = ReportDateSelection(startValue.takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
        endValue.takeIf { it.isNotEmpty() }?.let(LocalDate::parse), selectingEnd)
    val month = YearMonth.parse(monthValue)
    val days = remember(month) { calendarDays(month) }
    val shortDate = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy") }
    val spokenDate = remember { DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", CalendarLocale) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp).widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(26.dp), color = CalendarNavy, border = BorderStroke(1.dp, CalendarBorder), tonalElevation = 0.dp) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(5.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, CalendarBlue, CalendarCyan, Color.Transparent))))
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 12.dp)) {
                    Text("Período del reporte", color = CalendarWhite, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DateEndpoint("Desde", selection.start?.format(shortDate) ?: "Selecciona", !selectingEnd, Modifier.weight(1f)) {
                            selectingEnd = false
                            selection.start?.let { monthValue = YearMonth.from(it).toString() }
                        }
                        DateEndpoint("Hasta", selection.end?.format(shortDate) ?: "Selecciona", selectingEnd, Modifier.weight(1f)) {
                            selectingEnd = true
                            (selection.end ?: selection.start)?.let { monthValue = YearMonth.from(it).toString() }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MonthArrow(false) { monthValue = month.minusMonths(1).toString() }
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", CalendarLocale)).replaceFirstChar { it.titlecase(CalendarLocale) },
                            color = CalendarWhite, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                        MonthArrow(true) { monthValue = month.plusMonths(1).toString() }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        listOf("L", "M", "X", "J", "V", "S", "D").forEach { day ->
                            Text(day, color = CalendarMuted, fontSize = 12.sp, maxLines = 1,
                                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                        }
                    }
                    days.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { date ->
                                val endpoint = date == selection.start || date == selection.end
                                val inRange = selection.start != null && selection.end != null && date >= selection.start && date <= selection.end
                                val currentMonth = YearMonth.from(date) == month
                                Box(Modifier.weight(1f).height(46.dp)
                                    .background(if (inRange && !endpoint) CalendarBlue.copy(alpha = .16f) else Color.Transparent)
                                    .clip(RoundedCornerShape(8.dp))
                                    .semantics { contentDescription = date.format(spokenDate); selected = endpoint }
                                    .clickable(role = Role.Button) {
                                        val next = selection.select(date)
                                        startValue = next.start?.toString().orEmpty()
                                        endValue = next.end?.toString().orEmpty()
                                        selectingEnd = next.selectingEnd
                                        if (!currentMonth) monthValue = YearMonth.from(date).toString()
                                    }, contentAlignment = Alignment.Center) {
                                    if (endpoint) Box(Modifier.size(40.dp).background(
                                        Brush.radialGradient(listOf(CalendarCyan.copy(alpha = .35f), CalendarBlue.copy(alpha = .05f))), CircleShape))
                                    Box(Modifier.size(34.dp).background(if (endpoint) CalendarBlue else Color.Transparent, CircleShape),
                                        contentAlignment = Alignment.Center) {
                                        Text(date.dayOfMonth.toString(), color = if (endpoint || currentMonth) CalendarWhite else CalendarMuted.copy(alpha = .35f),
                                            fontSize = 14.sp, fontWeight = if (endpoint) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(if (selection.end == null) "Selecciona la fecha final." else if (!selection.valid) "El período no puede superar los 366 días."
                        else "Selecciona las fechas de inicio y fin.",
                        color = if (selection.end != null && !selection.valid) Color(0xFFFFAB13) else CalendarMuted,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CalendarBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = CalendarMuted),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text("Cancelar", maxLines = 1) }
                        Button(onClick = { if (selection.valid) onApply(requireNotNull(selection.start), requireNotNull(selection.end)) },
                            enabled = selection.valid, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CalendarBlue, contentColor = CalendarWhite),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text("Aplicar", maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateEndpoint(label: String, date: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = if (active) Color(0xFF082D58) else Color(0xFF051C36),
        border = BorderStroke(1.dp, if (active) CalendarCyan else CalendarBorder)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(label, color = if (active) CalendarCyan else CalendarMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(date, color = CalendarWhite, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MonthArrow(next: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp).background(Color(0xFF06203D), CircleShape)) {
        // Reuse the existing back arrow, rotated for the following month.
        RegisterIcon("back", CalendarWhite, if (next) "Mes siguiente" else "Mes anterior", 20.dp,
            Modifier.rotate(if (next) 180f else 0f))
    }
}

