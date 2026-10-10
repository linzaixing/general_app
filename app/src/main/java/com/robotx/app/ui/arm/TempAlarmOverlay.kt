package com.robotx.app.ui.arm

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.robotx.app.arm.model.TempPopup
import kotlinx.coroutines.delay

/** 与网页版一致的展示时长；最短触发间隔（20s 冷却）在 ArmRepository 里做。 */
private const val SHOW_MS = 5_000L

/**
 * 高温报警浮层：屏幕中央的闪烁卡片，5s 后自动消失。
 *
 * 卡片**不注册任何 pointerInput / clickable** —— 不消费触摸事件就等于穿透，
 * 报警期间用户仍能操作方向盘（网页版在这里会挡住摇杆）。
 */
@Composable
fun TempAlarmOverlay(
    popup: TempPopup?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = popup ?: return

    val transition = rememberInfiniteTransition(label = "temp-alarm")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 500), RepeatMode.Reverse),
        label = "temp-alarm-alpha",
    )

    LaunchedEffect(current) {
        delay(SHOW_MS)
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 320.dp)
                .alpha(alpha),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("电机高温报警", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(current.text, style = MaterialTheme.typography.bodyMedium)
                if (current.ids.isNotEmpty()) {
                    Text(
                        "涉及电机：" + current.ids.joinToString("、") { "ID$it" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "阈值 %.0f℃".format(current.threshold),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}