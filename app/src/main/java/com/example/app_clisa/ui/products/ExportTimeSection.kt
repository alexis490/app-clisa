package com.example.app_clisa.ui.products

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app_clisa.data.excel.*
private val TimeMuted = Color(0xFFA9C8EC)
private val TimeAmber = Color(0xFFFFBC39)

/** Only immutable results/callbacks, no repository or network operation in this component. */
@Composable
internal fun ExportSafetyReview(report: ExcelInventory, assessment: CompletenessAssessment, plan: SyncPlan?) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF03152C), contentColor = Color(0xFFF1F5FF), border = BorderStroke(1.dp, Color(0xFF07568B))) {
        Column(Modifier.fillMaxWidth().padding(12.dp).testTag("export-safety"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Validación del inventario de referencia", style = MaterialTheme.typography.titleSmall)
            Text("${assessment.state} · ${report.errorCount} incidencias", color = TimeAmber, fontSize = 12.sp)
            Text("El stock propuesto suma las cantidades válidas del Excel. No se descuentan traspasos históricos.", color = TimeMuted, fontSize = 11.sp)
            Text(if (assessment.technicalValid) "Lectura técnica válida; seleccionar el archivo no acredita su alcance completo."
                else "Hay incidencias o comprobaciones técnicas pendientes. Revisa las filas de origen.", color = TimeMuted, fontSize = 11.sp)
            Text("Productos y existencias ausentes se conservan sin cambios. No se habilitan eliminaciones automáticas.", color = TimeAmber, fontSize = 11.sp)
            Text("Operaciones con validaciones pendientes: ${plan?.operations?.count { it.changesInventory && it.blockers.isNotEmpty() } ?: "Plan pendiente"}", color = TimeAmber, fontSize = 11.sp)
            Text("Existencias ausentes para revisión: ${plan?.operations?.count { it.type == SyncOperationType.REVIEW_MISSING_EXISTENCE } ?: "Sin comparación"}", color = TimeMuted, fontSize = 11.sp)
        }
    }
}
