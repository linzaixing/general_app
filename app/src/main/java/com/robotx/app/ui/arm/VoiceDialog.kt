package com.robotx.app.ui.arm

import android.Manifest
import android.os.Build
import android.util.Base64
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.robotx.app.arm.ArmAudioRecorder
import com.robotx.app.arm.ArmViewModel
import com.robotx.app.arm.VoiceManager

/**
 * 语音输入弹窗（不再是左侧窗口的一个选项卡）。
 *
 * 链路：本机录 16kHz 单声道 WAV → Base64 → 服务端 `/api/voice_command` 做 ASR + 意图解析。
 * 不走手机自带的语音识别服务 —— 华为部分机型上根本没有，之前「点了没反应」就是因为它。
 *
 * 停止录音后**自动先跑一次 dryRun（只解析意图、不动机械臂）**，把识别文本和意图摆出来，
 * 用户确认后再点「执行」；ASR 和模型都可能听错，而 grab 是真的会动。
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VoiceDialog(
    vm: ArmViewModel,
    onClose: () -> Unit,
) {
    val voice by vm.voice.collectAsState()
    val permission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val recorder = remember { ArmAudioRecorder() }

    var recording by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }

    // 正在录音时被关掉弹窗，必须把麦克风还回去。
    DisposableEffect(Unit) {
        onDispose { recorder.release() }
    }

    // Dialog 是一个**独立窗口**：默认不隐藏系统栏、挖孔模式也是 DEFAULT。
    // 弹窗一出现，系统按「最上层窗口」重新评估栏的可见性 —— 底下的主窗口会被
    // 重新派发一次非零 insets，主界面的 safeDrawing padding 随之变化，表现为
    // 「打开语音弹窗，底层画面比例被挤压」。这里把弹窗窗口配置成与主窗口一致：
    // 隐藏系统栏 + 挖孔短边模式 + adjustResize，弹窗开关期间主窗口的布局参数不再被联动。
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        val window = (dialogView.parent as? DialogWindowProvider)?.window
        window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.getInsetsController(w, dialogView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    val result = voice.result

    // 第一轮（dryRun）识别出的文本直接灌进输入框，第二轮执行复用它、不重复跑 ASR。
    val transcript = result?.transcript.orEmpty()
    LaunchedEffect(transcript) {
        if (transcript.isNotBlank()) text = transcript
    }

    // 服务端的回复（识别失败、意图确认、执行结果…）一并念出来。
    // 只念 reply，不念 transcript —— transcript 就是用户自己刚说的话，念它纯属噪音。
    LaunchedEffect(result) {
        val reply = result?.reply.orEmpty()
        if (reply.isNotBlank()) VoiceManager.speak(reply)
    }

    fun startRecording() {
        localError = null
        // 必须先掐掉播报：麦克风会把扬声器里的播报当成人声收进去，
        // 上一次的「急停已触发」会被 ASR 识别成新指令。
        VoiceManager.stop()
        vm.clearVoice()
        if (recorder.start()) {
            recording = true
        } else {
            localError = "麦克风不可用：请检查是否被其他应用占用。"
        }
    }

    fun stopAndRecognize() {
        recording = false
        val wav = recorder.stop()
        if (wav == null) {
            localError = "没录到声音（太短了），请按住多说几句再停。"
            return
        }
        val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        vm.voiceCommandAudio(b64, dryRun = true)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.9f)
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "语音输入",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text("关闭") }
                }

                Text(
                    text = "说一句话，先看它被理解成什么意图，确认无误再执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        permission.status != PermissionStatus.Granted -> {
                            Text(
                                text = "需要录音权限才能说话。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = { permission.launchPermissionRequest() },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("授予录音权限") }
                        }

                        recording -> {
                            Button(
                                onClick = { stopAndRecognize() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("停止并识别")
                            }
                            Text(
                                text = "正在录音…说完点上面的按钮。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        else -> {
                            Button(
                                onClick = { startRecording() },
                                enabled = !voice.loading,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (voice.loading) "识别中…" else "开始录音")
                            }
                        }
                    }

                    localError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    voice.error?.let {
                        Text(
                            text = "错误：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Text(
                        text = "或者直接打字（录音不可用时用这条）",
                        style = MaterialTheme.typography.labelLarge,
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

                    result?.let {
                        // 识别结果区整体调小一档（v1.0.15：14→11sp / 12→10sp）：
                        // 长回复在 0.72 屏宽的弹窗里两三行就折行，小一号更耐读。
                        Text(
                            "识别文本：${it.transcript.ifBlank { "-" }}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        )
                        Text(
                            "回复：${it.reply}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        )
                        it.intent?.let { intent ->
                            Text(
                                text = "意图：$intent",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                ),
                            )
                        }
                        Text(
                            text = if (it.dryRun) "（本次只解析，未执行）"
                            else if (it.executed) "已执行" else "未执行",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}