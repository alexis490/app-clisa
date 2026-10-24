package com.example.app_clisa.ui.users

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun UserSymbol(kind: String, color: Color, description: String?) {
    Canvas(Modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        val s = size.width / 24f
        val stroke = Stroke(1.8f * s)
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(color, Offset(a*s,b*s), Offset(c*s,d*s), stroke.width, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, Offset(x*s,y*s), Size(w*s,h*s), CornerRadius(1.5f*s), style = stroke)
        when (kind) {
            "menu" -> listOf(5f,12f,19f).forEach { drawCircle(color,1.5f*s,Offset(12*s,it*s)) }
            "copy" -> { rect(8f,7f,12f,15f); line(15f,3f,4f,3f); line(4f,3f,4f,17f) }
            "document" -> { rect(4f,2f,16f,20f); line(8f,8f,16f,8f); line(8f,12f,16f,12f); line(8f,16f,14f,16f) }
            "lock" -> { rect(4f,10f,16f,12f); drawArc(color,180f,180f,false,Offset(7*s,0f),Size(10*s,20*s),style=stroke); line(12f,15f,12f,18f) }
            "shield" -> drawPath(Path().apply { moveTo(12*s,2*s); lineTo(21*s,6*s); lineTo(21*s,12*s); quadraticTo(21*s,19*s,12*s,23*s); quadraticTo(3*s,19*s,3*s,12*s); lineTo(3*s,6*s); close() },color,style=stroke)
            "play" -> drawPath(Path().apply { moveTo(5*s,2*s); lineTo(22*s,12*s); lineTo(5*s,22*s); close() },color,style=stroke)
            "check" -> { drawCircle(color,10*s,Offset(12*s,12*s),style=stroke); line(6f,12f,10f,16f); line(10f,16f,18f,8f) }
            "add" -> { drawCircle(color,4*s,Offset(10*s,6*s),style=stroke); drawArc(color,180f,180f,false,Offset(2*s,12*s),Size(16*s,20*s),style=stroke); line(2f,22f,18f,22f); line(20f,5f,20f,13f); line(16f,9f,24f,9f) }
        }
    }
}
