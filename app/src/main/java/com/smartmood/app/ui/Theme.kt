package com.smartmood.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartmood.app.domain.Creature
import com.smartmood.app.domain.Eyes
import com.smartmood.app.domain.Shape

val Ink = Color(0xFF3B2F2F)
val Paper = Color(0xFFFBF6F0)
val Accent = Color(0xFF2F6F73)

private val SmartMoodColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFFE58E26),
    background = Paper,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1E9DE),
    outline = Color(0xFFD8CBB8),
)

@Composable
fun SmartMoodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SmartMoodColors, content = content)
}

/** 小精灵的平面示意图。locked 为 true 时只显示灰色剪影。 */
@Composable
fun CreatureArt(creature: Creature, modifier: Modifier = Modifier, size: Dp = 96.dp, locked: Boolean = false) {
    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) { drawCreature(creature, locked) }
    }
}

fun DrawScope.drawCreature(creature: Creature, locked: Boolean) {
    val body = if (locked) Color(0xFFD9D2C7) else Color(creature.body)
    val accent = if (locked) Color(0xFFCBC2B4) else Color(creature.accent)
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h * 0.56f

    val (bw, bh) = when (creature.shape) {
        Shape.ROUND -> 0.66f to 0.60f
        Shape.TALL -> 0.52f to 0.74f
        Shape.FLAT -> 0.76f to 0.46f
        Shape.DROP -> 0.58f to 0.68f
        Shape.SPIKY -> 0.62f to 0.58f
        Shape.PETAL -> 0.58f to 0.56f
    }
    val bodyW = w * bw
    val bodyH = h * bh

    when (creature.shape) {
        Shape.SPIKY -> {
            val spikes = 5
            for (i in 0 until spikes) {
                val t = (i + 0.5f) / spikes
                val x = cx - bodyW / 2f + bodyW * t
                val baseY = cy - bodyH * 0.40f
                val path = Path().apply {
                    moveTo(x - w * 0.055f, baseY)
                    lineTo(x, baseY - h * 0.15f)
                    lineTo(x + w * 0.055f, baseY)
                    close()
                }
                drawPath(path, accent)
            }
        }
        Shape.PETAL -> {
            val petals = 6
            for (i in 0 until petals) {
                val angle = Math.toRadians((360.0 / petals) * i)
                val px = cx + (bodyW * 0.56f) * Math.cos(angle).toFloat()
                val py = cy + (bodyH * 0.56f) * Math.sin(angle).toFloat()
                drawCircle(accent, radius = w * 0.1f, center = Offset(px, py))
            }
        }
        Shape.DROP -> {
            val path = Path().apply {
                moveTo(cx, cy - bodyH * 0.66f)
                lineTo(cx - bodyW * 0.22f, cy - bodyH * 0.30f)
                lineTo(cx + bodyW * 0.22f, cy - bodyH * 0.30f)
                close()
            }
            drawPath(path, body)
        }
        Shape.ROUND -> {
            drawCircle(body, radius = w * 0.075f, center = Offset(cx - bodyW * 0.30f, cy - bodyH * 0.44f))
            drawCircle(body, radius = w * 0.075f, center = Offset(cx + bodyW * 0.30f, cy - bodyH * 0.44f))
        }
        else -> Unit
    }

    drawOval(body, topLeft = Offset(cx - bodyW / 2f, cy - bodyH / 2f), size = Size(bodyW, bodyH))
    drawOval(
        Color.White.copy(alpha = if (locked) 0f else 0.28f),
        topLeft = Offset(cx - bodyW * 0.30f, cy - bodyH * 0.38f),
        size = Size(bodyW * 0.30f, bodyH * 0.18f),
    )

    if (locked) return

    val eyeY = cy - bodyH * 0.06f
    val eyeDx = bodyW * 0.20f
    val eyeR = w * 0.036f
    val ink = Color(0xFF3B2F2F)
    when (creature.eyes) {
        Eyes.OPEN -> {
            drawOval(ink, Offset(cx - eyeDx - eyeR, eyeY - eyeR * 1.3f), Size(eyeR * 2f, eyeR * 2.6f))
            drawOval(ink, Offset(cx + eyeDx - eyeR, eyeY - eyeR * 1.3f), Size(eyeR * 2f, eyeR * 2.6f))
        }
        Eyes.SLEEPY -> {
            drawArc(
                ink, 0f, 180f, false, Offset(cx - eyeDx - eyeR * 1.2f, eyeY - eyeR), Size(eyeR * 2.4f, eyeR * 2f),
                style = Stroke(width = w * 0.016f, cap = StrokeCap.Round),
            )
            drawArc(
                ink, 0f, 180f, false, Offset(cx + eyeDx - eyeR * 1.2f, eyeY - eyeR), Size(eyeR * 2.4f, eyeR * 2f),
                style = Stroke(width = w * 0.016f, cap = StrokeCap.Round),
            )
        }
        Eyes.HAPPY -> {
            drawArc(
                ink, 180f, 180f, false, Offset(cx - eyeDx - eyeR * 1.2f, eyeY - eyeR * 0.6f), Size(eyeR * 2.4f, eyeR * 2f),
                style = Stroke(width = w * 0.016f, cap = StrokeCap.Round),
            )
            drawArc(
                ink, 180f, 180f, false, Offset(cx + eyeDx - eyeR * 1.2f, eyeY - eyeR * 0.6f), Size(eyeR * 2.4f, eyeR * 2f),
                style = Stroke(width = w * 0.016f, cap = StrokeCap.Round),
            )
        }
    }
    drawOval(
        Color(0xFFF08A7E).copy(alpha = 0.55f),
        Offset(cx - bodyW * 0.40f, eyeY + eyeR * 1.2f), Size(w * 0.075f, h * 0.045f),
    )
    drawOval(
        Color(0xFFF08A7E).copy(alpha = 0.55f),
        Offset(cx + bodyW * 0.40f - w * 0.075f, eyeY + eyeR * 1.2f), Size(w * 0.075f, h * 0.045f),
    )
    drawArc(
        ink, 20f, 140f, false, Offset(cx - w * 0.045f, eyeY + eyeR * 0.6f), Size(w * 0.09f, h * 0.06f),
        style = Stroke(width = w * 0.014f, cap = StrokeCap.Round),
    )
}
