package com.robotx.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * HUD 视觉基元：深空网格底 + 荧光切角面板。
 *
 * 这两个是整套界面的「语法」，只在这里定义一次：
 * 左窗、右操作台、欢迎页都用同一份，避免三处各画一套逐渐走形。
 */

/**
 * 深空背景：径向渐变 + 细网格。
 *
 * 网格不是装饰 —— 它让空的地方也有参照系，面板因此**不需要阴影就能浮起来**
 * （深色界面里阴影本来就几乎看不见，靠明度差才可靠）。
 * 间距固定 40dp，1080p 横屏下约 30 条线，每帧重绘的开销可忽略。
 */
fun Modifier.hudBackdrop(): Modifier = drawBehind {
    // ① 底色：中心偏上最亮，四角压到最深，像屏幕中央有一盏顶灯。
    drawRect(
        Brush.radialGradient(
            colors = listOf(HudPanel, HudDeep, HudVoid),
            center = Offset(size.width * 0.5f, size.height * 0.28f),
            radius = size.maxDimension * 0.95f,
        ),
    )

    // ② 网格：每 5 格一条主线，用来数距离（轮盘行程、滑条高度都靠它做视觉标尺）。
    val step = 40.dp.toPx()
    if (step <= 0f) return@drawBehind

    fun line(alpha: Float, width: Float, start: Offset, end: Offset) {
        drawLine(color = HudGrid.copy(alpha = alpha), start = start, end = end, strokeWidth = width)
    }

    var i = 0
    var x = 0f
    while (x <= size.width) {
        val major = i % 5 == 0
        line(if (major) 0.55f else 0.26f, if (major) 1.5f else 1f, Offset(x, 0f), Offset(x, size.height))
        x += step
        i++
    }
    i = 0
    var y = 0f
    while (y <= size.height) {
        val major = i % 5 == 0
        line(if (major) 0.55f else 0.26f, if (major) 1.5f else 1f, Offset(0f, y), Offset(size.width, y))
        y += step
        i++
    }
}

/**
 * 面板外框：一圈细荧光描边 + 四角切角标记。
 *
 * 四角短线是瞄准具 / HUD 的通用语汇 —— 框住一块区域，但不让它看起来像一个「卡片盒子」。
 * 因此配套的面板必须用**直角**（`RectangleShape`）：圆角与直角标记放一起会互相打架。
 *
 * 用 `drawWithContent` 而非 `drawBehind`：线必须压在面板底色之上，
 * `drawBehind` 会被 Surface 自己的背景盖掉。
 */
fun Modifier.hudFrame(
    color: Color = HudCyan,
    edgeAlpha: Float = 0.22f,
    cornerAlpha: Float = 0.85f,
    cornerLength: Dp = 16.dp,
    edgeWidth: Dp = 1.dp,
    cornerWidth: Dp = 2.dp,
): Modifier = drawWithContent {
    drawContent()

    val ew = edgeWidth.toPx()
    val cw = cornerWidth.toPx()
    val cl = cornerLength.toPx().coerceAtMost(size.minDimension * 0.35f)
    val inset = ew / 2f

    drawRect(
        color = color.copy(alpha = edgeAlpha),
        topLeft = Offset(inset, inset),
        size = Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f)),
        style = Stroke(width = ew),
    )

    // 四个角：每个角画一条横线 + 一条竖线，朝面板内部延伸。
    val corners = listOf(
        Triple(Offset(inset, inset), 1f, 1f),
        Triple(Offset(size.width - inset, inset), -1f, 1f),
        Triple(Offset(inset, size.height - inset), 1f, -1f),
        Triple(Offset(size.width - inset, size.height - inset), -1f, -1f),
    )
    corners.forEach { (p, dx, dy) ->
        val c = color.copy(alpha = cornerAlpha)
        drawLine(c, p, Offset(p.x + dx * cl, p.y), cw)
        drawLine(c, p, Offset(p.x, p.y + dy * cl), cw)
    }
}