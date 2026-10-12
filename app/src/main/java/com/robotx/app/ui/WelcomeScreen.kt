package com.robotx.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robotx.app.R
import com.robotx.app.arm.ArmConfig
import com.robotx.app.arm.ArmViewModel
import com.robotx.app.arm.model.ArmLinkState
import com.robotx.app.ui.theme.EnableGreen
import com.robotx.app.ui.theme.HudAmber
import com.robotx.app.ui.theme.hudBackdrop
import com.robotx.app.ui.theme.hudFrame

/**
 * 欢迎 / 引导页 —— App 的第一屏，也是「层层递进」的第一层。
 *
 * 三层结构：
 *   ① 选择连接方式（WiFi / 蓝牙）
 *   ② 填写服务地址（选 WiFi 后才展开）
 *   ③ 建立通信（点连接 → 通道真的 ONLINE 才进控制台）
 *
 * 另外留了一条**离线预览**的路：底部「跳过，先看控制台」。
 * 没联网也想先看看控制台长什么样时走这条；进去后使能 / 方向 / 急停都会被拦下并提示先联网，
 * 不会让人误以为已经在控臂。3D 仿真用的是本地 assets，离线照样能看。
 *
 * 蓝牙通道（BleArmTransport）还没实现，这里只放一个禁用入口 + 「即将支持」，
 * 不做假 UI —— 点了没反应比明说「还没做」更糟。
 *
 * 2026-10-08（v1.0.12）：底色换成 [hudBackdrop] 的深空网格，
 * 卡片换成直角 + 荧光切角框（[hudFrame]），与横屏控制台统一。
 *
 * @param onConnected 通道 ONLINE 后进控制台（正常控制）。
 * @param onSkip      不连接、直接进控制台（离线预览）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    onConnected: () -> Unit,
    onSkip: () -> Unit,
    vm: ArmViewModel = viewModel(),
) {
    val cfg by vm.config.collectAsState()
    val ports by vm.ports.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanError by vm.scanError.collectAsState()
    val link by vm.link.collectAsState()

    var method by rememberSaveable { mutableStateOf("") } // "" 未选 | "wifi"
    var host by rememberSaveable { mutableStateOf("") }
    var portText by rememberSaveable { mutableStateOf(ArmConfig.DEFAULT_PORT.toString()) }
    var mode by rememberSaveable { mutableStateOf(ArmConfig.Mode.SIM.value) }
    var serialPort by rememberSaveable { mutableStateOf("") }
    var mapping by rememberSaveable { mutableStateOf("") }
    var camWristText by rememberSaveable { mutableStateOf(ArmConfig.DEFAULT_CAM_WRIST.toString()) }
    var camGlobalText by rememberSaveable {
        mutableStateOf(if (ArmConfig.DEFAULT_CAM_GLOBAL >= 0) ArmConfig.DEFAULT_CAM_GLOBAL.toString() else "")
    }
    // 故意不用 rememberSaveable：从控制台返回欢迎页时要重新归零，
    // 否则「连接中」的状态会被恢复，一进页面就被已经 ONLINE 的链路立刻弹回控制台。
    var attempting by remember { mutableStateOf(false) }
    var seeded by remember { mutableStateOf(false) }

    // 已保存的配置只回填一次，之后不覆盖用户正在输入的内容。
    LaunchedEffect(cfg) {
        if (!seeded) {
            host = cfg.host
            portText = cfg.port.toString()
            mode = cfg.mode
            serialPort = cfg.serialPort
            mapping = cfg.mapping
            camWristText = if (cfg.camWrist >= 0) cfg.camWrist.toString() else ""
            camGlobalText = if (cfg.camGlobal >= 0) cfg.camGlobal.toString() else ""
            seeded = true
        }
    }

    // 第三层的出口：只有通道真的连上（ONLINE）才放行，连不上就留在这一页看原因。
    LaunchedEffect(link, attempting) {
        if (attempting && link == ArmLinkState.ONLINE) onConnected()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .hudBackdrop(),
        // 内容整体垂直居中，而不是贴着顶边。
        // ⚠️ 居中靠外层 Box 的 contentAlignment，不能靠 Column 的 verticalArrangement ——
        // 滚动容器会把 Column 的高度约束成「内容高度」，Column 自己撑不到满屏，
        // `Arrangement.Center` 就没有可居中的余量（内容一屏装得下时毫无效果）。
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo_deeparm),
                contentDescription = "DeepArm",
                modifier = Modifier
                    .size(148.dp)
                    .clip(RoundedCornerShape(28.dp)),
            )
            Text(
                text = "deeparm_console",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "先把手机和机械臂接上，再进控制台",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            StepCard(step = 1, title = "选择连接方式", active = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MethodTile(
                        title = "WiFi 局域网",
                        desc = "手机与电脑同一网络",
                        icon = Icons.Filled.Wifi,
                        selected = method == "wifi",
                        enabled = true,
                        onClick = { method = "wifi" },
                        modifier = Modifier.weight(1f),
                    )
                    MethodTile(
                        title = "蓝牙",
                        desc = "即将支持",
                        icon = Icons.Filled.Bluetooth,
                        selected = false,
                        enabled = false,
                        onClick = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            AnimatedVisibility(visible = method == "wifi") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StepCard(step = 2, title = "填写服务地址", active = true) {
                        Text(
                            text = "填运行机械臂服务的那台电脑的 IP 和端口（默认 ${ArmConfig.DEFAULT_PORT}）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("服务地址（电脑 IP）") },
                            placeholder = { Text("192.168.1.100") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter { c -> c.isDigit() } },
                            label = { Text("端口") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text("模式", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ArmConfig.Mode.entries.forEach { m ->
                                FilterChip(
                                    selected = mode == m.value,
                                    onClick = { mode = m.value },
                                    label = { Text(m.label) },
                                )
                            }
                        }

                        if (mode == ArmConfig.Mode.REAL.value) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = serialPort,
                                    onValueChange = { serialPort = it },
                                    label = { Text("串口（真机必填）") },
                                    placeholder = { Text("COM5") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { vm.scanPorts() }, enabled = !scanning) {
                                    if (scanning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Filled.Refresh, contentDescription = "扫描串口")
                                    }
                                }
                            }
                            scanError?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (ports.isNotEmpty()) {
                                Text("可用串口（点击填入）", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ports.forEach { p ->
                                        OutlinedButton(onClick = { serialPort = p.device }) {
                                            Text(
                                                if (p.description.isBlank()) p.device
                                                else "${p.device} ${p.description}"
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = mapping,
                            onValueChange = { mapping = it },
                            label = { Text("映射文件路径（可留空）") },
                            placeholder = { Text("留空则用服务端默认映射") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // 相机设备号：**留空 = 不打开该路**（服务端就不会去连它）。
                        // 必须填「连续多帧内容会变」的活相机索引：本机只有 #1 是真的，
                        // #4 能打开但六帧字节完全相同（虚拟/占位设备），#0 能开却读不到帧、
                        // 一次失败重试要 39 s —— 填错会把同进程的关节指令链路一起拖慢。
                        Text(
                            text = "相机设备号（留空 = 不打开该路；必须填活相机索引，本机只有 #1 是真的）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = camWristText,
                                onValueChange = { camWristText = it.filter { c -> c.isDigit() } },
                                label = { Text("腕部相机 ID") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = camGlobalText,
                                onValueChange = { camGlobalText = it.filter { c -> c.isDigit() } },
                                label = { Text("全局相机 ID") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    StepCard(step = 3, title = "建立通信", active = true) {
                        LinkStatusRow(link = link, attempting = attempting)
                        // 同一个位置按连接状态切换：连上了就是「断开连接」，否则是「连接」。
                        // 断开只走 vm.disconnect()，不触发 onConnected，所以点了会留在本页。
                        if (link == ArmLinkState.ONLINE) {
                            Button(
                                onClick = {
                                    attempting = false
                                    vm.disconnect()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("断开连接")
                            }
                        } else {
                            Button(
                                onClick = {
                                    val p = portText.toIntOrNull() ?: ArmConfig.DEFAULT_PORT
                                    attempting = true
                                    vm.connect(
                                        host = host,
                                        port = p,
                                        mode = mode,
                                        serialPort = serialPort,
                                        mapping = mapping,
                                        // 留空 = 不打开该路（哨兵 CAM_OFF），而不是「退回某个默认索引」：
                                        // 默认索引在本机可能是假设备，塞进去只会拖慢整条链路。
                                        camWrist = camWristText.toIntOrNull() ?: ArmConfig.CAM_OFF,
                                        camGlobal = camGlobalText.toIntOrNull() ?: ArmConfig.CAM_OFF,
                                    )
                                },
                                enabled = host.isNotBlank() && !attempting,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (attempting) "连接中…" else "连接")
                            }
                            if (attempting) {
                                OutlinedButton(
                                    onClick = {
                                        attempting = false
                                        vm.disconnect()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("取消")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "连接成功后进入横屏控制台：左侧一窗切换 相机 / 仿真 / 语音，右侧方向操作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 420.dp),
            )

            // 离线预览入口：没网也能先看控制台（3D 仿真走本地 assets）。
            TextButton(onClick = onSkip) {
                Text("跳过，先看控制台")
            }
            Text(
                text = "现在没联网也能进去看界面和本地 3D 仿真；要真的操作机械臂时，App 会提醒你回来连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

/** 带序号圆点的步骤卡片，序号把「层层递进」的顺序摆明。 */
@Composable
private fun StepCard(
    step: Int,
    title: String,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    // 与横屏控制台同一套 HUD 语法：直角 + 荧光切角框（见 ui/theme/Hud.kt）。
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).hudFrame(),
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = step.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

/** 连接方式磁贴：选中给主色描边，禁用则整体置灰并显示 desc 里的原因。 */
@Composable
private fun MethodTile(
    title: String,
    desc: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = when {
        selected -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val fg = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline

    Surface(
        modifier = modifier
            .hudFrame(color = border, edgeAlpha = 0.35f, cornerAlpha = 0.6f)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else fg,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else fg,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 第 3 步的状态行：把通道状态直白地翻成中文，连不上时用户知道卡在哪。 */
@Composable
private fun LinkStatusRow(link: ArmLinkState, attempting: Boolean) {
    val (text, color) = when (link) {
        ArmLinkState.ONLINE -> "已连上服务端" to LinkOkColor
        ArmLinkState.CONNECTING -> "正在连接…" to LinkWaitColor
        ArmLinkState.RETRYING -> "连接中断，正在重连…请检查 IP / 端口 / 电脑防火墙" to LinkWaitColor
        ArmLinkState.CLOSED -> "已断开" to MaterialTheme.colorScheme.error
        ArmLinkState.IDLE -> if (attempting) "准备连接…" to LinkWaitColor
        else "尚未连接" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private val LinkOkColor = EnableGreen
private val LinkWaitColor = HudAmber