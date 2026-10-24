package com.example.app_clisa.ui.records

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.app_clisa.data.FormalizationState
import com.example.app_clisa.ui.registration.RegisterIcon

private val FWhite=Color(0xFFF1F5FF)
private val FMuted=Color(0xFFABC7EC)
private val FOrange=Color(0xFFFF9D0A)
private val FGreen=Color(0xFF00E67B)

@Composable
internal fun FormalizationSymbol(state: FormalizationState, modifier: Modifier = Modifier) {
    val tint=when(state) { FormalizationState.PENDING->FOrange;FormalizationState.COMPLETED->FGreen;else->FMuted }
    Canvas(modifier) {
            val r=size.minDimension*.43f;val c=center;val w=size.minDimension*.075f
            drawCircle(tint, r,c,style=if(state==FormalizationState.COMPLETED) androidx.compose.ui.graphics.drawscope.Fill else Stroke(w))
            if(state==FormalizationState.PENDING) {
                drawLine(tint,c,c+Offset(0f,-r*.6f),w,StrokeCap.Round)
                drawLine(tint,c,c+Offset(r*.48f,r*.25f),w,StrokeCap.Round)
            } else if(state==FormalizationState.COMPLETED) {
                val ink=Color(0xFF002A22)
                drawLine(ink,c+Offset(-r*.5f,0f),c+Offset(-r*.1f,r*.35f),w,StrokeCap.Round)
                drawLine(ink,c+Offset(-r*.1f,r*.35f),c+Offset(r*.55f,-r*.4f),w,StrokeCap.Round)
            } else {
                // Draw the question mark in the same coordinates as its ring;
                // font baselines/padding must not displace it on different phones.
                val question=Path().apply {
                    moveTo(c.x-r*.28f,c.y-r*.24f)
                    cubicTo(c.x-r*.28f,c.y-r*.62f,c.x+r*.36f,c.y-r*.62f,c.x+r*.3f,c.y-r*.2f)
                    cubicTo(c.x+r*.28f,c.y,c.x,c.y-r*.02f,c.x,c.y+r*.2f)
                }
                drawPath(question,tint,style=Stroke(w,cap=StrokeCap.Round))
                drawCircle(tint,w*.6f,c+Offset(0f,r*.5f))
            }
    }
}

@Composable
internal fun FormalizationBadge(state: FormalizationState, enabled: Boolean, id: String, onClick: () -> Unit) {
    val tint=when(state) { FormalizationState.PENDING->FOrange;FormalizationState.COMPLETED->FGreen;else->FMuted }
    Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(13.dp),color=Color(0xFF03152C),
        border=BorderStroke(1.dp,tint),modifier=Modifier.testTag("formal-status-$id")) {
        Row(Modifier.background(Brush.verticalGradient(listOf(tint.copy(alpha=.18f),tint.copy(alpha=.04f))))
            .heightIn(min=32.dp).padding(horizontal=8.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            FormalizationSymbol(state,Modifier.size(18.dp))
            Text(state.label,color=tint,fontSize=10.sp,fontWeight=FontWeight.Medium,maxLines=1)
        }
    }
}

@Composable
internal fun FormalizationDialog(product: String, code: String, busy: Boolean, error: String?, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest={if(!busy)onCancel()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=480.dp).fillMaxWidth().padding(14.dp),color=Color(0xFF03152C),contentColor=FWhite,
            shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,Color(0xFF07568B))) {
            Column(Modifier.heightIn(max=700.dp).verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement=Arrangement.spacedBy(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    IconButton(enabled=!busy,onClick=onCancel,modifier=Modifier.size(28.dp).testTag("formal-close")) { Text("×",color=FMuted,fontSize=28.sp) }
                }
                Box(Modifier.size(64.dp)
                    .background(Brush.radialGradient(listOf(FOrange.copy(alpha=.25f),FOrange.copy(alpha=.07f))),CircleShape)
                    .border(1.dp,FOrange,CircleShape),contentAlignment=Alignment.Center) {
                    FormalizationSymbol(FormalizationState.PENDING,Modifier.size(42.dp))
                }
                Text("¿Confirmas que este traspaso ya fue registrado en el sistema principal?",textAlign=TextAlign.Center,
                    style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text("Al marcar como completado, este registro se indicará como finalizado en la aplicación. Esta acción no se puede deshacer",
                    color=FMuted,textAlign=TextAlign.Center,style=MaterialTheme.typography.bodyMedium)
                Surface(shape=RoundedCornerShape(13.dp),color=Color(0xFF062343),border=BorderStroke(1.dp,Color(0xFF07568B))) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        RegisterIcon("product",FWhite,null,38.dp)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(product,color=FWhite,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold)
                            Text("Código: $code",color=FMuted,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                error?.let { Text(it,color=Color(0xFFFFA4A4),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("formal-error")) }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick=onCancel,enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=56.dp).testTag("formal-cancel"),
                        shape=RoundedCornerShape(13.dp),border=BorderStroke(1.dp,Color(0xFF07568B))) { Text("Cancelar",color=FMuted) }
                    Button(onClick=onConfirm,enabled=!busy,modifier=Modifier.weight(1.35f).heightIn(min=56.dp).testTag("formal-confirm"),
                        shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.buttonColors(containerColor=FGreen,contentColor=Color(0xFF00152D))) {
                        if(busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                        else Text("✓  Marcar como completado",textAlign=TextAlign.Center,fontSize=12.sp)
                    }
                }
            }
        }
    }
}
