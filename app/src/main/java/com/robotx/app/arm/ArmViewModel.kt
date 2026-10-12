package com.robotx.app.arm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotx.app.arm.model.VisionDetectDto
import com.robotx.app.arm.model.VisionGrabDto
import com.robotx.app.arm.model.VoiceResultDto
import com.robotx.app.arm.transport.HttpArmApi
import com.robotx.app.network.ApiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 连接页、主控页与工具页共用的 ViewModel：把 [ArmRepository] 的只读流暴露给 Compose，
 * 并把 UI 动作转成仓库调用。
 */
class ArmViewModel : ViewModel() {

    val state = ArmRepository.state
    val logs = ArmRepository.logs
    val link = ArmRepository.link
    val tempPopup = ArmRepository.tempPopup
    val graspResult = ArmRepository.graspResult
    val trajFiles = ArmRepository.trajFiles
    val config = ArmConfig.state

    /** 串口扫描结果（连接页下拉）。 */
    private val _ports = MutableStateFlow<List<HttpArmApi.SerialPortDto>>(emptyList())
    val ports: StateFlow<List<HttpArmApi.SerialPortDto>> = _ports.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    // ------------------------------------------------------------ 语音（两阶段）

    /** 语音请求的界面状态：`result` 里 `dryRun=true` 的那次是「意图预览」。 */
    data class VoiceUi(
        val loading: Boolean = false,
        val result: VoiceResultDto? = null,
        val error: String? = null,
    )

    private val _voice = MutableStateFlow(VoiceUi())
    val voice: StateFlow<VoiceUi> = _voice.asStateFlow()

    // ------------------------------------------------------------ 视觉（两阶段）

    data class VisionUi(
        val detecting: Boolean = false,
        val detect: VisionDetectDto? = null,
        val grabbing: Boolean = false,
        val grab: VisionGrabDto? = null,
        val error: String? = null,
    )

    private val _vision = MutableStateFlow(VisionUi())
    val vision: StateFlow<VisionUi> = _vision.asStateFlow()

    fun saveConfig(
        host: String,
        port: Int,
        mode: String,
        serialPort: String,
        mapping: String,
        camWrist: Int,
        camGlobal: Int,
    ) = viewModelScope.launch {
        ArmConfig.update(
            host = host,
            port = port,
            mode = mode,
            serialPort = serialPort,
            mapping = mapping,
            camWrist = camWrist,
            camGlobal = camGlobal,
        )
    }

    /** 保存配置并连接。 */
    fun connect(
        host: String,
        port: Int,
        mode: String,
        serialPort: String,
        mapping: String,
        camWrist: Int,
        camGlobal: Int,
    ) = viewModelScope.launch {
        ArmConfig.update(
            host = host,
            port = port,
            mode = mode,
            serialPort = serialPort,
            mapping = mapping,
            camWrist = camWrist,
            camGlobal = camGlobal,
        )
        ArmRepository.connect(ArmConfig.snapshot())
    }

    fun disconnect() = ArmRepository.disconnect()

    fun dismissTempPopup() = ArmRepository.dismissTempPopup()

    // ------------------------------------------------------------ 运动控制

    fun enable() = ArmRepository.enable()

    fun disable() = ArmRepository.disable()

    fun estop() = ArmRepository.estop()

    fun setCartMode(on: Boolean) = ArmRepository.setCartMode(on)

    /** 方向点动一帧，见 [ArmRepository.cartJog] 的方向与高度语义。 */
    fun cartJog(lx: Double, ly: Double, ry: Double, dt: Double, factor: Double) =
        ArmRepository.cartJog(lx, ly, ry, dt, factor)

    /** 开/关定点旋转（RCM）模式，与方向控制互斥，见 [ArmRepository.setRcmMode]。 */
    fun setRcmMode(on: Boolean) = ArmRepository.setRcmMode(on)

    /** 定点旋转一帧（轮盘 X=roll / Y=pitch），见 [ArmRepository.rcmJog]。 */
    fun rcmJog(droll: Double, dpitch: Double, dt: Double, factor: Double) =
        ArmRepository.rcmJog(droll, dpitch, dt, factor)

    // ------------------------------------------------------------ 夹爪 / 示教 / 回放

    fun gripper(action: String, pos: Double? = null) = ArmRepository.gripper(action, pos)

    fun record(action: String, label: String = "") = ArmRepository.record(action, label)

    fun replay(file: String, speed: Double = 1.0, loop: Int = 1) =
        ArmRepository.replay(file, speed, loop)

    fun replayStop() = ArmRepository.replayStop()

    fun refreshTrajList() = viewModelScope.launch { ArmRepository.loadTrajList() }

    /** 扫描服务端所在电脑的串口列表。 */
    fun scanPorts() {
        val cfg = ArmConfig.snapshot()
        if (!cfg.isConfigured) {
            _scanError.value = "请先填写服务地址"
            return
        }
        viewModelScope.launch {
            _scanning.value = true
            _scanError.value = null
            HttpArmApi(cfg.endpoint()).serialPorts()
                .onSuccess { _ports.value = it }
                .onFailure { _scanError.value = it.message ?: "扫描失败" }
            _scanning.value = false
        }
    }

    // ------------------------------------------------------------ 语音指令

    /**
     * 文本指令。[dryRun] 为 true 时服务端只解析意图、不动机械臂 —— UI 必须先走这一步，
     * 把意图给用户看过、确认后再以 `dryRun=false` 调一次真执行。
     */
    fun voiceCommand(text: String, dryRun: Boolean) {
        if (text.isBlank()) {
            _voice.value = VoiceUi(error = "请输入指令文本")
            return
        }
        sendVoice(text = text, audioB64 = null, dryRun = dryRun)
    }

    /**
     * 录音指令：[wavBase64] 是 16kHz 单声道 WAV 的 Base64（见 [ArmAudioRecorder]），
     * 识别交给服务端做 —— 不依赖手机自带的语音识别服务，华为部分机型上根本没有。
     */
    fun voiceCommandAudio(wavBase64: String, dryRun: Boolean) {
        if (wavBase64.isBlank()) {
            _voice.value = VoiceUi(error = "没录到音频，请重试")
            return
        }
        sendVoice(text = null, audioB64 = wavBase64, dryRun = dryRun)
    }

    private fun sendVoice(text: String?, audioB64: String?, dryRun: Boolean) {
        val cfg = ArmConfig.snapshot()
        if (!cfg.isConfigured) {
            _voice.value = VoiceUi(error = "请先填写服务地址")
            return
        }
        val modelCfg = ApiConfig.snapshot()
        viewModelScope.launch {
            _voice.value = VoiceUi(loading = true)
            HttpArmApi(cfg.endpoint())
                .voiceCommand(
                    apiKey = modelCfg.apiKey,
                    cam = "wrist",
                    dryRun = dryRun,
                    text = text,
                    audioB64 = audioB64,
                    asrModel = modelCfg.asrModel,
                    chatModel = modelCfg.chatModel,
                )
                .onSuccess { _voice.value = VoiceUi(result = it) }
                .onFailure { _voice.value = VoiceUi(error = it.message ?: "请求失败") }
        }
    }

    fun clearVoice() {
        _voice.value = VoiceUi()
    }

    // ------------------------------------------------------------ 视觉

    /** 只做颜色检测、只显示结果，绝不动作。 */
    fun visionDetect(cam: String, preset: String) {
        val cfg = ArmConfig.snapshot()
        if (!cfg.isConfigured) {
            _vision.value = VisionUi(error = "请先填写服务地址")
            return
        }
        viewModelScope.launch {
            _vision.value = _vision.value.copy(detecting = true, error = null, grab = null)
            HttpArmApi(cfg.endpoint()).visionDetect(cam, preset)
                .onSuccess { _vision.value = _vision.value.copy(detecting = false, detect = it) }
                .onFailure {
                    _vision.value = _vision.value.copy(detecting = false, error = it.message ?: "检测失败")
                }
        }
    }

    /** 视觉伺服抓取：会真的动机械臂，调用前 UI 必须已经拿到用户确认。 */
    fun visionGrab(cam: String, preset: String, descendM: Double?, close: Boolean?) {
        val cfg = ArmConfig.snapshot()
        if (!cfg.isConfigured) {
            _vision.value = _vision.value.copy(error = "请先填写服务地址")
            return
        }
        viewModelScope.launch {
            _vision.value = _vision.value.copy(grabbing = true, error = null)
            HttpArmApi(cfg.endpoint()).visionGrab(cam = cam, preset = preset, descendM = descendM, close = close)
                .onSuccess { _vision.value = _vision.value.copy(grabbing = false, grab = it) }
                .onFailure {
                    _vision.value = _vision.value.copy(grabbing = false, error = it.message ?: "抓取失败")
                }
        }
    }

    fun clearVisionResult() {
        _vision.value = VisionUi()
    }
}