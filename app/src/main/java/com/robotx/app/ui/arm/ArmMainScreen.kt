package com.robotx.app.ui.arm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robotx.app.arm.ArmViewModel
import com.robotx.app.arm.JoyJogEffect
import com.robotx.app.arm.model.ArmLinkState
import com.robotx.app.ui.SettingsDialog
import com.robotx.app.ui.theme.EnableGreen
import com.robotx.app.ui.theme.HudAmber
import com.robotx.app.ui.theme.hudBackdrop
import com.robotx.app.ui.theme.hudFrame

/**
 * 控制台（横屏）：左边一窗两画面（相机 / 仿真），右边方向操作。
 *
 * 主界面刻意极简 —— 只留「使能 / 失能 / 急停」三个会改变机械臂状态的功能键，
 * 方向盘占满右侧中心；速度档、方向开关、运行状态、告警、日志全部收进工具栏浮层，
 * 避免操作者在一堆控件里找方向盘。
 *
 * 离线预览模式（首页点「跳过，先看控制台」进来，见 [preview]）：界面和本地 3D 仿真
 * 照常可用，但使能 / 方向 / 急停这类会真的驱动机械臂的操作一律拦下，改为提示先回去连接。
 *
 * @param onBack  返回欢迎页。
 * @param preview true = 从首页「跳过」进来的离线预览，未连上服务端时拦截控制操作。
 */
@Composable
fun ArmMainScreen(
    onBack: () -> Unit,
    preview: Boolean = false,
    vm: ArmViewModel = viewModel(),
) {
    val link by vm.link.collectAsState()
    val state by vm.state.collectAsState()
    val logs by vm.logs.collectAsState()
    val tempPopup by vm.tempPopup.collectAsState()
    val cfg by vm.config.collectAsState()

    var axis by remember { mutableStateOf(0.0 to 0.0) }
    var lift by remember { mutableStateOf(0.0) }
    var padActive by remember { mutableStateOf(false) }
    var sliderActive by remember { mutableStateOf(false) }
    var factor by remember { mutableStateOf(0.5) }
    var showTools by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showNeedNet by remember { mutableStateOf(false) }

    val linkOnline = link == ArmLinkState.ONLINE
    // 离线预览：从首页跳过进来且还没连上服务端 —— 只看界面，不许动机械臂。
    val previewOffline = preview && !linkOnline
    // 方向控制要三件事同时成立：通道在线、已使能、服务端处于笛卡尔模式。
    val jogEnabled = linkOnline && state.enabled && state.cartMode
    val endpoint = remember(cfg) { if (cfg.isConfigured) cfg.endpoint() else null }

    // 轮盘与高度滑条共用同一个 20Hz 发送器：任意一个按住就开动。
    val jogging = padActive || sliderActive

    JoyJogEffect(
        active = jogging,
        enabled = jogEnabled,
        axis = axis,
        lift = lift,
        factor = factor,
        onFrame = { lx, ly, ry, dt, f -> vm.cartJog(lx, ly, ry, dt, f) },
    )

    // 「推不动」的唯一反馈渠道：服务端门禁拒绝时只写日志、不回包。
    val lastWarn = logs.lastOrNull { it.level == "warn" || it.level == "warning" || it.level == "error" }

    Box(modifier = Modifier.fillMaxSize().hudBackdrop()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                // 刻意**不做** insets padding：系统栏已全局隐藏（瞬态栏是浮层、不改 insets），
                // 挖孔区也要有画面（见 MainActivity 的 SHORT_EDGES）。这里只留固定 8dp 安全边，
                // 布局尺寸不再随 insets 变化 —— 弹窗开/关、栏显/隐时主画面比例都恒定。
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 左：画面窗口（相机 / 仿真），整块纵向空间都给画面。
            ArmViewWindow(
                joints = state.joints,
                camera = state.camera,
                endpoint = endpoint,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            // 右：操作区（比例放大），轮盘 + 高度滑条撑满剩余空间。
            ControlDeck(
                linkOnline = linkOnline,
                offlinePreview = previewOffline,
                enabled = state.enabled,
                jogEnabled = jogEnabled,
                gripperEnabled = linkOnline && state.enabled,
                onPadActive = { padActive = it },
                onPadAxis = { lx, ly -> axis = lx to ly },
                onSliderActive = { sliderActive = it },
                onLift = { lift = it },
                onGripperOpen = { vm.gripper("open") },
                onGripperClose = { vm.gripper("close") },
                onEnable = { if (previewOffline) showNeedNet = true else vm.enable() },
                onDisable = { if (previewOffline) showNeedNet = true else vm.disable() },
                onEstop = { if (previewOffline) showNeedNet = true else vm.estop() },
                onVoice = { if (previewOffline) showNeedNet = true else showVoice = true },
                onNeedNetwork = { showNeedNet = true },
                onBack = onBack,
                onTools = { showTools = true },
                onSettings = { showSettings = true },
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
            )
        }

        if (showTools) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                ArmToolsPanel(
                    vm = vm,
                    factor = factor,
                    onFactor = { factor = it },
                    lastWarn = lastWarn,
                    onClose = { showTools = false },
                )
            }
        }

        TempAlarmOverlay(
            popup = tempPopup,
            onDismiss = { vm.dismissTempPopup() },
        )

        if (showVoice) {
            VoiceDialog(vm = vm, onClose = { showVoice = false })
        }

        if (showSettings) {
            SettingsDialog(onDismiss = { showSettings = false })
        }

        // 离线预览时被拦下的操作统一弹这个：说清为什么、以及怎么走出去。
        if (showNeedNet) {
            AlertDialog(
                onDismissRequest = { showNeedNet = false },
                title = { Text("需要先连接服务端") },
                text = {
                    Text(
                        "现在是离线预览，只能看界面和本地 3D 仿真，还不能操作机械臂。\n" +
                            "请返回首页填好电脑 IP，连上服务端后再操作。"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showNeedNet = false
                            onBack()
                        },
                    ) { Text("返回首页连接") }
                },
                dismissButton = {
                    TextButton(onClick = { showNeedNet = false }) { Text("继续预览") }
                },
            )
        }
    }
}

/**
 * 右侧操作区：一行薄图标条 + 一行状态小字 + 撑满剩余空间的「轮盘 ‖ 高度滑条」。
 *
 * 布局只服务于一件事：**把面积让给操作控件**。
 * 所以全套控制入口压成一行无文字图标（使能 / 失能 / 急停 / 工具 / 设置），
 * 原来那排「使能/失能/急停」文字按钮整块删掉 —— 它自己占了 ~110dp 高度，
 * 而横屏手机总共才 ~390dp；再叠上「前后左右」四键十字（比轮盘还宽），
 * 方向盘被挤得几乎没法用。现在标题与状态都降到 9~11sp，其余全给控件。
 *
 * 「断开连接」不在这里：它和「连接」是同一个开关的两面，统一放在首页第 3 步的
 * 同一个位置（见 [com.robotx.app.ui.WelcomeScreen]），控制台上不再重复入口。
 *
 * 急停仍然是这一行里最大、最显眼的一个（红色实心圆），不能因为「省地方」被弱化。
 *
 * 2026-10-08 科技感改版（v1.0.12）：整块操作区换成一套「深空 HUD」——
 * 外框由主色细描边改为 [hudFrame] 的荧光切角框（直角，与四角标记配套），
 * 背景由纯色改为 [hudBackdrop] 的深空网格，标题前加一道荧光竖条，
 * 状态行改用等宽字体、把「不能动」的原因标成琥珀色。
 * 语音输入键不进这一行，改摆在轮盘左上角（见 [VoiceKey]）：
 * 轮盘是圆的，方框四角本来就是摸不到的空白，白得的地方正好放它。
 * 夹爪键同理摆在右上角（见 [GripperKey]）：按住张开、松手合爪。
 *
 * @param jogEnabled     未使能 / 未连 / 非笛卡尔模式时为 false，轮盘与滑条整体置灰。
 * @param gripperEnabled 未连 / 未使能时为 false：夹爪键置灰不响应（夹爪不依赖笛卡尔模式）。
 * @param offlinePreview 离线预览：按钮保持可点（点了弹提示），控件区盖一层拦截层。
 * @param onVoice        语音输入：打开录音识别弹窗（见 [VoiceDialog]）。
 * @param onNeedNetwork  离线预览时点到控制类操作的回调（弹「先去联网」提示）。
 */
@Composable
private fun ControlDeck(
    linkOnline: Boolean,
    offlinePreview: Boolean,
    enabled: Boolean,
    jogEnabled: Boolean,
    gripperEnabled: Boolean,
    onPadActive: (Boolean) -> Unit,
    onPadAxis: (lx: Double, ly: Double) -> Unit,
    onSliderActive: (Boolean) -> Unit,
    onLift: (Double) -> Unit,
    onGripperOpen: () -> Unit,
    onGripperClose: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onEstop: () -> Unit,
    onVoice: () -> Unit,
    onNeedNetwork: () -> Unit,
    onBack: () -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canEnable = offlinePreview || (linkOnline && !enabled)
    val canDisable = offlinePreview || (linkOnline && enabled)
    val canEstop = offlinePreview || linkOnline
    val canVoice = offlinePreview || linkOnline
    val dim = MaterialTheme.colorScheme.outline
    val idle = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier.hudFrame(),
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 横屏下不设 TopAppBar：56dp 的标题栏在这里就是 1/7 的可用高度。
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeckIconButton(
                    onClick = onBack,
                    enabled = true,
                    contentDescription = "返回",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    tint = idle,
                )
                // 标题前的荧光短竖条：给这一行一个视觉起点，也让「机械臂控制」读起来像一块仪表铭牌。
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp, end = 6.dp)
                        .width(3.dp)
                        .height(13.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = "机械臂控制",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 使能与失能：绿 / 灰的电感图标，一眼能分出「现在是哪种状态可以点」。
                DeckIconButton(
                    onClick = onEnable,
                    enabled = canEnable,
                    contentDescription = "使能",
                    icon = Icons.Filled.PowerSettingsNew,
                    tint = if (canEnable) EnableGreen else dim,
                )
                DeckIconButton(
                    onClick = onDisable,
                    enabled = canDisable,
                    contentDescription = "失能",
                    icon = Icons.Filled.PowerOff,
                    tint = if (canDisable) idle else dim,
                )
                // 急停：这一行里唯一更大的按钮，也换成正规的 stop sign 样式
                // （红圆底 + 白八角 + STOP 字样，见 [EstopButton]）。
                EstopButton(
                    onClick = onEstop,
                    enabled = canEstop,
                )
                DeckIconButton(
                    onClick = onTools,
                    enabled = true,
                    contentDescription = "工具栏",
                    icon = Icons.Filled.Build,
                    tint = idle,
                )
                DeckIconButton(
                    onClick = onSettings,
                    enabled = true,
                    contentDescription = "设置",
                    icon = Icons.Filled.Settings,
                    tint = idle,
                )
            }

            Text(
                text = when {
                    offlinePreview -> "离线预览 · 操作机械臂请先回首页连接服务端"
                    !linkOnline -> "未连接服务端 · 方向控制不可用"
                    !enabled -> "未使能 · 先点使能，再到工具栏开「方向控制」"
                    !jogEnabled -> "方向控制已关闭 · 到工具栏打开「方向控制」"
                    else -> "按住轮盘走前后左右 · 拖右侧刻度升 / 降 · 点右上角开 / 合夹爪"
                },
                // 「不能动」的原因用琥珀标出来，正常提示走灰 —— 一行字也要能一眼分出好坏。
                color = if (jogEnabled) MaterialTheme.colorScheme.onSurfaceVariant else HudAmber,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            if (offlinePreview) {
                Text(
                    text = "离线预览：界面与本地 3D 仿真可看，使能 / 方向 / 急停会提示先联网",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // 控件区：轮盘吃满剩余宽度，高度滑条固定窄条贴右边。
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 轮盘区：把轮盘约束成**与可用高度等边的正方形并居中**，
                    // 背景圆 / 十字参考线的圆心因此永远落在可用区域的几何中心，
                    // 左右留白对称（旧实现直接 fillMaxSize，宽屏下圆心虽居中，
                    // 但角键与圆的相对位置随屏宽漂移，看起来像「中心有偏移」）。
                    // 左上角语音键、右上角夹爪键锚在正方形自己的两个上角，
                    // 与圆缘保持固定间距，不再被多余的水平留白推开。
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(1f),
                        ) {
                            JoyPad(
                                enabled = jogEnabled,
                                onActiveChange = onPadActive,
                                onAxisChange = onPadAxis,
                                modifier = Modifier.fillMaxSize(),
                            )
                            VoiceKey(
                                onClick = onVoice,
                                enabled = canVoice,
                                modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
                            )
                            GripperKey(
                                onOpen = onGripperOpen,
                                onClose = onGripperClose,
                                enabled = gripperEnabled,
                                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                            )
                        }
                    }
                    HeightSlider(
                        enabled = jogEnabled,
                        onActiveChange = onSliderActive,
                        onValueChange = onLift,
                        modifier = Modifier.width(46.dp).fillMaxHeight(),
                    )
                }
                // 离线预览时盖一层「拦截层」：轮盘 / 滑条的按下动作转成提示，而不是摇动机械臂。
                if (offlinePreview) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onNeedNetwork() },
                    )
                }
            }
        }
    }
}

/**
 * 急停按钮：正规 stop sign 样式 —— **红色圆底 + 白色八角形 + 中间红字 STOP**，
 * 全部用 Canvas 自绘，不引图片资源。
 *
 * 为什么不复用 [DeckIconButton] + `Icons.Filled.Stop`：那个图标是「实心圆里一个方停」，
 * 与「失能」的电感图标在小尺寸下几乎分不出来。八角 STOP 是公路标志的通用语汇，
 * 操作者在慌乱中扫一眼也能认出「这是立即停车」，不能长得像普通功能键。
 * 置灰态整体降饱和（红→轮廓色），但仍保持八角结构，可辨识度不丢。
 */
@Composable
private fun EstopButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val red = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    val octagon = if (enabled) Color.White else MaterialTheme.colorScheme.surface
    val stopPaint = remember(enabled) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = red.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            // ① 红色圆底。
            drawCircle(color = red, radius = r, center = c)
            // ② 圆底内缘一圈细白环：给八角留出「镶边」，更像标志牌。
            drawCircle(
                color = octagon.copy(alpha = 0.8f),
                radius = r * 0.92f,
                center = c,
                style = Stroke(1.5f),
            )
            // ③ 白色八角形（正八边形，顶点从 22.5° 起保证上下边水平）。
            val path = Path()
            for (k in 0 until 8) {
                val ang = Math.PI / 8 + k * Math.PI / 4
                val px = c.x + (kotlin.math.cos(ang) * r * 0.72f).toFloat()
                val py = c.y + (kotlin.math.sin(ang) * r * 0.72f).toFloat()
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, octagon)
            // ④ 八角中央的 STOP 红字（Compose 无原生 drawText，借 nativeCanvas）。
            stopPaint.textSize = r * 0.34f
            drawIntoCanvas { canvas ->
                val fm = stopPaint.fontMetrics
                val baseline = c.y - (fm.ascent + fm.descent) / 2f
                canvas.nativeCanvas.drawText("STOP", c.x, baseline, stopPaint)
            }
        }
    }
}

/**
 * 控制条上的圆形图标按钮 —— 刻意不用 `IconButton`。
 *
 * `IconButton` 会被 Material 的 `minimumInteractiveComponentSize` 强制撑到 48dp，
 * 这一行要放返回 + 5 个图标（48×6=288dp，横屏手机的右栏只有 ~420dp），会被顶到放不下。
 * 这里用 `Box` + `clickable` 把尺寸压到 40dp（急停 44dp），把空间还给操作控件。
 *
 * 2026-10-08：默认底色从透明改成一层淡的设备面（`surfaceVariant`），
 * 让图标读起来像一排「实体键」而不是悬空符号；急停仍用实心红覆盖掉它。
 */
@Composable
private fun DeckIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    icon: ImageVector,
    tint: Color,
    containerColor: Color? = null,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                color = containerColor ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 语音输入键：摆在方向轮盘左上角的小圆键 + 一行「语音」小字。
 *
 * 为什么带文字：它从顶部图标行挪到了轮盘角落，位置变「偏」了，
 * 只留一个麦克风图标容易被当成装饰；8sp 的两个字足够说明它是能按的。
 * 只有圆键本身可点，文字不参与触摸（免得误触范围比看到的更大）。
 */
@Composable
private fun VoiceKey(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.5.dp, accent.copy(alpha = 0.8f), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "语音输入",
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "语音",
            fontSize = 8.sp,
            color = accent,
        )
    }
}

/**
 * 夹爪键：摆在方向轮盘右上角的小圆键 + 一行「夹爪」小字，与左上角的语音键对称。
 *
 * 交互是**循环开关（toggle）**：点一下下发 `gripper open`（进入张开态），
 * 再点下发 `gripper close`（回到闭合态），如此往复 —— 消息协议与工具面板的
 * 夹爪卡完全同一路（见 [com.robotx.app.arm.ArmRepository.gripper]）。
 * （v1.0.14 的「按住张开、松手合爪」实测表现为只能关不能开，已废弃。）
 *
 * 视觉反映当前状态：张开态用张开箭头图标 + 荧光高亮底、角标文字「开」；
 * 闭合态用合拢箭头图标 + 常规底色、角标文字「合」。
 */
@Composable
private fun GripperKey(
    onOpen: () -> Unit,
    onClose: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    if (open) accent.copy(alpha = 0.22f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    1.5.dp,
                    if (open) accent else accent.copy(alpha = 0.5f),
                    CircleShape,
                )
                .clickable(enabled = enabled) {
                    open = !open
                    if (open) onOpen() else onClose()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (open) Icons.Filled.OpenInFull else Icons.Filled.CloseFullscreen,
                contentDescription = if (open) "夹爪已张开，点击合爪" else "夹爪已闭合，点击张爪",
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = if (open) "开" else "合",
            fontSize = 8.sp,
            color = accent,
            fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}