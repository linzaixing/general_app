package com.robotx.app.ui.theme

import android.app.Activity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * App 配色：**只有深色一套**。
 *
 * 2026-10-08 起不再跟随系统亮暗。旧实现是 `darkTheme = isSystemInDarkTheme()`，
 * 手机处于浅色模式时整个 App 会渲染成白底（用户反馈「背景是纯白色」），
 * 既与这套 HUD 视觉冲突，也让青色荧光在浅底上完全读不出来。
 * 所以这里**强制深色**，与系统设置无关 —— 遥控台本来也不该有白底版本。
 */
private val HudColors = darkColorScheme(
    // 主色：全屏唯一的荧光色，承载「可以操作 / 正在通信」。
    primary = HudCyan,
    onPrimary = HudVoid,
    primaryContainer = HudCyanDeep,
    onPrimaryContainer = HudCyan,
    inversePrimary = HudCyanDeep,

    // 次要：走轮廓色，避免出现第二种「强调」。
    secondary = HudOutline,
    onSecondary = HudText,
    secondaryContainer = HudRaisedHi,
    onSecondaryContainer = HudText,

    // 第三色：离线预览横幅等「说明性」容器。
    tertiary = HudCyan,
    onTertiary = HudVoid,
    tertiaryContainer = HudCyanDeep,
    onTertiaryContainer = HudCyan,

    background = HudDeep,
    onBackground = HudText,

    surface = HudPanel,
    onSurface = HudText,
    surfaceVariant = HudRaised,
    onSurfaceVariant = HudTextDim,

    // Material 3 的 surface 层级：不显式给值会落回基线深色（偏中性灰），
    // 与这里的蓝黑体系不搭，所以整条梯度都按 HUD 明度阶梯铺一遍。
    surfaceContainerLowest = HudVoid,
    surfaceContainerLow = HudPanel,
    surfaceContainer = HudRaised,
    surfaceContainerHigh = HudRaisedHi,
    surfaceContainerHighest = Color(0xFF22384B),
    surfaceDim = HudVoid,
    surfaceBright = Color(0xFF274055),

    outline = HudOutline,
    outlineVariant = HudGrid,

    // 急停与告警：全屏唯一的暖色。
    error = RecordingRed,
    onError = Color.White,
    errorContainer = Color(0xFF3A1119),
    onErrorContainer = Color(0xFFFFB3BC),
)

/**
 * 应用主题入口。
 *
 * 刻意**不暴露 `darkTheme` 参数**：让「只此一套深色」成为编译期事实，
 * 而不是调用点可以随手传错的一个选项。
 */
@Composable
fun RobotXTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = HudColors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = HudColors,
        typography = RobotXTypography,
    ) {
        // 默认正文色必须在**主题根**给一次。
        // Material 3 只在 `Surface` 内部才设置 LocalContentColor，没包在 Surface 里的
        // 裸 Text（欢迎页标题就是这种）会退回 `Color.Black` —— 在深色底上直接看不见。
        // 放在这里等于给「没有 Surface 的那一层」兜底，不用每个界面各记一次。
        CompositionLocalProvider(LocalContentColor provides HudColors.onBackground) {
            content()
        }
    }
}