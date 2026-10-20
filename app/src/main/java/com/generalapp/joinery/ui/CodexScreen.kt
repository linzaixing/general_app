package com.generalapp.joinery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generalapp.joinery.domain.JointType
import com.generalapp.joinery.domain.ProgressSnapshot

@Composable
fun CodexScreen(progress: ProgressSnapshot) {
    LazyColumn(
        Modifier.fillMaxSize().background(Ink),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("榫卯图鉴", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("通关对应关卡后解锁", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
        }
        items(JointType.entries.toList()) { joint ->
            val unlocked = progress.unlockedJoint(joint)
            JointCard(joint, unlocked)
        }
    }
}

@Composable
private fun JointCard(joint: JointType, unlocked: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (unlocked) Panel else Panel.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (unlocked) Brass.copy(alpha = 0.35f) else Color(0x14FFFFFF),
                RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .background(PanelHigh, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (unlocked) {
                JointSchematic(joint)
            } else {
                Text("？", color = Muted, fontSize = 24.sp)
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (unlocked) joint.title else "未解锁",
                    color = if (unlocked) Paper else Muted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (unlocked) {
                    Spacer(Modifier.size(8.dp))
                    Text(joint.subtitle, color = Brass, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (unlocked) joint.description else "完成使用这类榫卯的关卡即可解锁。",
                color = if (unlocked) Muted else Muted.copy(alpha = 0.7f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun JointSchematic(joint: JointType) {
    val line = Brass
    Canvas(Modifier.size(46.dp)) {
        val w = size.width
        val h = size.height
        val thin = Stroke(width = 2.2f)
        when (joint) {
            JointType.STRAIGHT -> {
                drawRect(line, Offset(w * 0.18f, h * 0.28f), Size(w * 0.64f, h * 0.44f), style = thin)
                drawLine(line, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.5f, h * 0.72f), strokeWidth = 2.2f)
            }
            JointType.DOVETAIL -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.16f, h * 0.6f)
                    lineTo(w * 0.36f, h * 0.3f)
                    lineTo(w * 0.64f, h * 0.3f)
                    lineTo(w * 0.84f, h * 0.6f)
                    lineTo(w * 0.64f, h * 0.75f)
                    lineTo(w * 0.36f, h * 0.75f)
                    close()
                }
                drawPath(path, line, style = thin)
            }
            JointType.SHOULDER -> {
                drawRect(line, Offset(w * 0.16f, h * 0.24f), Size(w * 0.68f, h * 0.2f), style = thin)
                drawRect(line, Offset(w * 0.32f, h * 0.44f), Size(w * 0.36f, h * 0.36f), style = thin)
            }
            JointType.CORNER -> {
                drawLine(line, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.2f), strokeWidth = 2.2f)
                drawLine(line, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.8f, h * 0.8f), strokeWidth = 2.2f)
                drawLine(line, Offset(w * 0.8f, h * 0.8f), Offset(w * 0.2f, h * 0.8f), strokeWidth = 2.2f)
                drawLine(line, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.2f, h * 0.2f), strokeWidth = 2.2f)
                drawLine(line, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.8f), strokeWidth = 2.2f)
            }
            JointType.WEDGE -> {
                drawLine(line, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.85f, h * 0.5f), strokeWidth = 2.2f)
                drawLine(line, Offset(w * 0.42f, h * 0.3f), Offset(w * 0.58f, h * 0.7f), strokeWidth = 2.2f)
                drawLine(line, Offset(w * 0.58f, h * 0.3f), Offset(w * 0.42f, h * 0.7f), strokeWidth = 2.2f)
            }
            JointType.SLIDE -> {
                drawLine(line, Offset(w * 0.18f, h * 0.62f), Offset(w * 0.5f, h * 0.62f), strokeWidth = 2.2f)
                drawLine(line, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.82f, h * 0.38f), strokeWidth = 2.2f)
                drawLine(line, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.5f, h * 0.62f), strokeWidth = 2.2f)
                drawCircle(line, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.5f), style = thin)
            }
            JointType.THROUGH -> {
                drawRect(line, Offset(w * 0.14f, h * 0.3f), Size(w * 0.72f, h * 0.4f), style = thin)
                drawLine(line, Offset(w * 0.14f, h * 0.42f), Offset(w * 0.86f, h * 0.42f), strokeWidth = 2.2f)
                drawLine(line, Offset(w * 0.14f, h * 0.58f), Offset(w * 0.86f, h * 0.58f), strokeWidth = 2.2f)
            }
            JointType.CROSS -> {
                drawRect(line, Offset(w * 0.4f, h * 0.16f), Size(w * 0.2f, h * 0.68f), style = thin)
                drawRect(line, Offset(w * 0.16f, h * 0.4f), Size(w * 0.68f, h * 0.2f), style = thin)
            }
        }
    }
}
