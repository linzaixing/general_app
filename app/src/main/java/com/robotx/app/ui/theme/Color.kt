package com.robotx.app.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// 深空 HUD 调色板（2026-10-08 重做）
//
// 口径：整屏是一块「深空蓝黑底 + 青色荧光」的指挥台，只有四级明度 ——
//   底(最深) → 面(面板) → 线(网格/描边) → 荧光(主色)。
// 规则：**荧光色只用在「活的」东西上**（能操作、正在通信、正在动作），
//   静态装饰一律走线色，这样一眼扫过去就知道哪里可以按。
//   暖色只留给告警与急停，保证它在满屏青色里第一眼被看到。
// ─────────────────────────────────────────────────────────────────────────────

/** 屏幕四角的最深处：比面板更暗，让面板靠明度差浮起来（深色界面里阴影几乎不可见）。 */
val HudVoid = Color(0xFF04070C)

/** 页面底色（渐变中段）。 */
val HudDeep = Color(0xFF070E16)

/** 面板面：左侧画面窗 / 右侧操作台 / 工具栏卡片。 */
val HudPanel = Color(0xFF0C1620)

/** 面板上再抬一级：轮盘盘面、输入框底。 */
val HudRaised = Color(0xFF13212E)

/** 再抬一级：按下态底色、选中态容器。 */
val HudRaisedHi = Color(0xFF1A2C3C)

/** 网格线 / 分隔线。 */
val HudGrid = Color(0xFF14304A)

/** 次要轮廓（未使能的图标、禁用态描边）。 */
val HudOutline = Color(0xFF2A4A63)

/** 荧光主色：轮盘刻度环、面板切角、麦克风、选中态。 */
val HudCyan = Color(0xFF35E0FF)

/** 荧光主色压深一档：容器底、离焦强调。 */
val HudCyanDeep = Color(0xFF0E4C60)

/** 正文。 */
val HudText = Color(0xFFD8E7F4)

/** 次要文字 / 说明 / 刻度标注。 */
val HudTextDim = Color(0xFF7C93A8)

/** 告警琥珀：日志 warn、连接中、温度提示。 */
val HudAmber = Color(0xFFFFC24B)

/** 正常 / 已使能：偏青的绿，在蓝黑底上比纯绿更融，又不会和荧光主色混。 */
val EnableGreen = Color(0xFF2FD672)

/** 急停红：全屏唯一的暖色块，安全键必须第一眼被看到。 */
val RecordingRed = Color(0xFFFF4D5E)

/**
 * 急停深红：急停按钮的底色（v1.0.17）。圆底与八角形**同用此色**、中间不留白色区域，
 * 只留白色 STOP 字 —— 比原来的「红圆 + 白八角」更沉、更像真实的工业急停标牌。
 */
val EstopDarkRed = Color(0xFF8B0000)