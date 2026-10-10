package com.robotx.app.ui.arm

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robotx.app.arm.ArmViewModel
import com.robotx.app.arm.model.ArmLinkState
import com.robotx.app.arm.model.ArmLogLine
import com.robotx.app.ui.theme.EnableGreen
import com.robotx.app.ui.theme.HudAmber

/** 与服务端 `arm_control.vision.COLOR_PRESETS` 一致。 */
private val COLOR_PRESETS = listOf("red", "orange", "yellow", "green", "blue")
private val COLOR_LABELS = mapOf(
    "red" to "红", "orange" to "橙", "yellow" to "黄", "green" to "绿", "blue" to "蓝",
)

/** 速度档：直接作为 `cart_jog` 的 `factor`（服务端线速度上限 0.12 m/s）。 */
private val SPEED_STEPS = listOf(0.2, 0.5, 1.0)

/** 日志最多渲染多少条：这里是普通 Column，太多行会拖慢重组。 */
private const val LOG_LIMIT = 80

/**
 * 工具面板（全屏浮层）：运行状态、告警、方向控制、夹爪、示教/回放、语音、视觉、日志。
 *
 * 主界面只留「使能 / 失能 / 急停」三个功能键，其余全部折叠到这里，
 * 避免把方向盘挤没；两项真正会动机械臂的操作（语音真执行、视觉抓取）都强制二次确认。
 *
 * @param factor 方向速度档，由主界面持有（方向盘发送时要用），在这里改。
 * @param onFactor 修改速度档。
 * @param lastWarn 最近一条 warn/error 日志：服务端门禁拒绝时只写日志、不回包，
 *   这里是与「方向控制」卡相邻的唯一反馈位置。
 */
@Composable
fun ArmToolsPanel(
    vm: ArmViewModel,
    factor: Double,
    onFactor: (Double) -> Unit,
    lastWarn: ArmLogLine?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val link by vm.link.collectAsState()
    val logs by vm.logs.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("工具", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("关闭") }
        }

        StatusCard(link = link, state = state)
        DirectionCard(
            link = link,
            cartMode = state.cartMode,
            enabled = state.enabled,
            factor = factor,
            onFactor = onFactor,
            onCartMode = { vm.setCartMode(it) },
        )
        WarnCard(lastWarn)

        GripperCard(vm = vm, enabled = state.enabled)
        TeachCard(vm = vm, teaching = state.teaching, recState = state.rec.state)
        VoiceCard(vm = vm)
        VisionCard(vm = vm, enabled = state.enabled)
        LogCard(logs = logs)
    }
}

@Composable
private fun ToolCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** 运行状态：横屏高度有限，把连接/使能/推送/温度压成两行文字。 */
@Composable
private fun StatusCard(link: ArmLinkState, state: com.robotx.app.arm.model.ArmStateDto) {
    val temps = state.joints.mapNotNull { j -> j.temp?.let { j.id to it } } +
        listOfNotNull(state.gripper?.temp?.let { 6 to it })

    ToolCard("运行状态") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.size(10.dp).background(linkColor(link), CircleShape))
            Text(
                text = linkText(link),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            state.loopHz?.let {
                Text(
                    text = "循环 %.0fHz".format(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "推送 ${state.push.frames} 帧" +
                (state.push.age?.let { " / %.1fs".format(it) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = buildString {
                append("模式 ${state.mode}")
                append(" · ").append(if (state.enabled) "已使能" else "未使能")
                append(" · ").append(if (state.cartMode) "方向控制中" else "方向控制关")
                append(" · ").append(if (state.connected) "机械臂已连接" else "机械臂未连接")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (temps.isNotEmpty()) {
            Text(
                text = "温度 " + temps.joinToString(" ") { (id, t) -> "ID$id %.0f℃".format(t) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 方向控制开关 + 速度档：这两项决定方向盘能不能推、推多快。 */
@Composable
private fun DirectionCard(
    link: ArmLinkState,
    cartMode: Boolean,
    enabled: Boolean,
    factor: Double,
    onFactor: (Double) -> Unit,
    onCartMode: (Boolean) -> Unit,
) {
    val linkOnline = link == ArmLinkState.ONLINE

    ToolCard("方向控制") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("开启方向控制", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = cartMode,
                onCheckedChange = onCartMode,
                enabled = linkOnline && enabled,
            )
        }
        if (!linkOnline) {
            Text(
                text = "未连接服务端，无法开启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!enabled) {
            Text(
                text = "先使能机械臂，才能开启方向控制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(2.dp))
        Text("速度档（服务端上限 0.12 m/s）", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SPEED_STEPS.forEach { v ->
                FilterChip(
                    selected = factor == v,
                    onClick = { onFactor(v) },
                    label = { Text("%.1f×".format(v)) },
                )
            }
        }
    }
}

/** 告警卡：显示最近一条 warn/error（服务端门禁原因就出现在这里）。 */
@Composable
private fun WarnCard(line: ArmLogLine?) {
    val isProblem = line != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isProblem) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "最近告警",
                style = MaterialTheme.typography.titleMedium,
                color = if (isProblem) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = line?.text ?: "暂无告警",
                style = MaterialTheme.typography.bodySmall,
                color = if (isProblem) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 日志卡：普通 Column 渲染最后 [LOG_LIMIT] 条（外层已是 verticalScroll，不能再嵌 LazyColumn）。 */
@Composable
private fun LogCard(logs: List<ArmLogLine>) {
    val shown = logs.takeLast(LOG_LIMIT)

    ToolCard("实时日志（最近 ${logs.size} 条，显示末 ${shown.size} 条）") {
        if (shown.isEmpty()) {
            Text(
                "暂无日志",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                shown.forEach { line ->
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = levelColor(line.level),
                    )
                }
            }
        }
    }
}

@Composable
private fun GripperCard(vm: ArmViewModel, enabled: Boolean) {
    val state by vm.state.collectAsState()
    val gripper = state.gripper
    val lo = (gripper?.lo ?: 0.0).toFloat()
    val hi = (gripper?.hi ?: 1.0).toFloat()
    var pos by remember { mutableStateOf(lo) }

    ToolCard("夹爪（ID6）") {
        Text(
            text = gripper?.let {
                "位置 %.3f  目标 %s  温度 %s".format(
                    it.pos ?: 0.0,
                    it.target?.let { t -> "%.3f".format(t) } ?: "-",
                    it.temp?.let { t -> "%.0f℃".format(t) } ?: "-",
                )
            } ?: "服务端未上报夹爪（可能未配置 ID6）",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.gripper("open") }, enabled = enabled) { Text("开爪") }
            Button(onClick = { vm.gripper("close") }, enabled = enabled) { Text("合爪") }
        }
        if (hi > lo) {
            Text("指定位（%.3f ~ %.3f rad）".format(lo, hi), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = pos.coerceIn(lo, hi),
                onValueChange = { pos = it },
                valueRange = lo..hi,
                enabled = enabled,
            )
            Button(onClick = { vm.gripper("set", pos.toDouble()) }, enabled = enabled) {
                Text("设定位 %.3f".format(pos))
            }
        }
    }
}

@Composable
private fun TeachCard(vm: ArmViewModel, teaching: String, recState: String) {
    val trajFiles by vm.trajFiles.collectAsState()
    var label by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf("1.0") }
    var loop by remember { mutableStateOf("1") }

    ToolCard("示教与回放") {
        Text(
            text = "示教状态 $teaching / 记录 $recState",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.record("start", label) }) { Text("开始记录") }
            OutlinedButton(onClick = { vm.record("pause") }) { Text("暂停") }
            OutlinedButton(onClick = { vm.record("resume") }) { Text("继续") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.record("mark", label) }) { Text("打标记") }
            Button(onClick = { vm.record("stop") }) { Text("停止并保存") }
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("记录说明 / 标记名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("轨迹列表（点击选中）", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.refreshTrajList() }) { Text("刷新列表") }
        }
        if (trajFiles.isEmpty()) {
            Text("暂无轨迹文件", style = MaterialTheme.typography.bodySmall)
        } else {
            trajFiles.forEach { f ->
                FilterChip(
                    selected = selected == f.name,
                    onClick = { selected = f.name },
                    label = { Text("${f.name}（${f.n} 点）") },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = speed,
                onValueChange = { speed = it },
                label = { Text("速度倍率") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = loop,
                onValueChange = { loop = it },
                label = { Text("循环次数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.replay(
                        file = selected,
                        speed = speed.toDoubleOrNull() ?: 1.0,
                        loop = loop.toIntOrNull() ?: 1,
                    )
                },
                enabled = selected.isNotBlank(),
            ) { Text("回放") }
            OutlinedButton(onClick = { vm.replayStop() }) { Text("停止回放") }
        }
    }
}

@Composable
private fun VoiceCard(vm: ArmViewModel) {
    val voice by vm.voice.collectAsState()
    var text by remember { mutableStateOf("") }
    val result = voice.result

    ToolCard("语音指令") {
        Text(
            text = "主界面方向轮盘左上角的麦克风键可以直接录音识别；这里用于手打文本。先「解析意图」看它到底想干什么，确认无误再「执行」——ASR 与模型都可能听错。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("指令文本，例如：把红色方块抓起来") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.voiceCommand(text, dryRun = true) },
                enabled = !voice.loading && text.isNotBlank(),
            ) { Text("1. 解析意图") }
            Button(
                onClick = { vm.voiceCommand(text, dryRun = false) },
                enabled = !voice.loading && text.isNotBlank() && result?.dryRun == true,
            ) { Text("2. 执行") }
        }

        voice.error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
        result?.let {
            Text("识别文本：${it.transcript}", style = MaterialTheme.typography.bodySmall)
            Text("回复：${it.reply}", style = MaterialTheme.typography.bodySmall)
            it.intent?.let { intent ->
                Text(
                    text = "意图：$intent",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Text(
                text = if (it.dryRun) "（本次只解析，未执行）" else if (it.executed) "已执行" else "未执行",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun VisionCard(vm: ArmViewModel, enabled: Boolean) {
    val vision by vm.vision.collectAsState()
    var cam by remember { mutableStateOf("global") }
    var preset by remember { mutableStateOf("red") }
    var descend by remember { mutableStateOf("") }
    var closeJaw by remember { mutableStateOf(true) }
    var confirmGrab by remember { mutableStateOf(false) }

    ToolCard("视觉检测与抓取") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = cam == "wrist", onClick = { cam = "wrist" }, label = { Text("腕部") })
            FilterChip(selected = cam == "global", onClick = { cam = "global" }, label = { Text("全局") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            COLOR_PRESETS.forEach { p ->
                FilterChip(
                    selected = preset == p,
                    onClick = { preset = p },
                    label = { Text(COLOR_LABELS[p] ?: p) },
                )
            }
        }
        Button(
            onClick = { vm.visionDetect(cam, preset) },
            enabled = !vision.detecting && !vision.grabbing,
        ) { Text(if (vision.detecting) "检测中…" else "只检测（不动）") }

        vision.detect?.let { d ->
            Text(
                text = if (d.found) {
                    "检出：中心 (%.0f, %.0f)  面积 %s  用时 %sms".format(
                        d.cx ?: 0.0, d.cy ?: 0.0,
                        d.area?.let { "%.0f".format(it) } ?: "-",
                        d.ms?.let { "%.0f".format(it) } ?: "-",
                    )
                } else {
                    "未检出目标（正常结果）：${d.msg}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = descend,
                onValueChange = { descend = it },
                label = { Text("下降距离(m)，留空=只对准") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Switch(checked = closeJaw, onCheckedChange = { closeJaw = it })
            Text("合爪", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { confirmGrab = true },
            enabled = enabled && !vision.grabbing && (vision.detect?.found == true),
        ) { Text("执行抓取（会动）") }

        vision.error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
        vision.grab?.let { g ->
            Text(
                text = if (g.ok) {
                    "抓取成功：%d 轮，距离 %.3f m，合爪=%s".format(
                        g.rounds, g.descendedM ?: 0.0, if (g.closed) "是" else "否",
                    )
                } else {
                    "抓取未完成：${g.reason}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmGrab) {
        AlertDialog(
            onDismissRequest = { confirmGrab = false },
            title = { Text("确认执行视觉抓取？") },
            text = {
                Text("机械臂会真的运动（对准 → 下降 → 合爪）。请确认工作空间内无人、末端在安全位置。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmGrab = false
                    vm.visionGrab(
                        cam = cam,
                        preset = preset,
                        descendM = descend.toDoubleOrNull(),
                        close = closeJaw,
                    )
                }) { Text("执行") }
            },
            dismissButton = {
                TextButton(onClick = { confirmGrab = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun levelColor(level: String): Color = when (level.lowercase()) {
    "error" -> MaterialTheme.colorScheme.error
    "warn", "warning" -> HudAmber
    "history" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun linkColor(link: ArmLinkState): Color = when (link) {
    ArmLinkState.ONLINE -> EnableGreen
    ArmLinkState.CONNECTING, ArmLinkState.RETRYING -> HudAmber
    else -> MaterialTheme.colorScheme.error
}

private fun linkText(link: ArmLinkState): String = when (link) {
    ArmLinkState.IDLE -> "未连接"
    ArmLinkState.CONNECTING -> "正在连接…"
    ArmLinkState.ONLINE -> "已连接服务端"
    ArmLinkState.RETRYING -> "连接中断，重连中…"
    ArmLinkState.CLOSED -> "已断开"
}