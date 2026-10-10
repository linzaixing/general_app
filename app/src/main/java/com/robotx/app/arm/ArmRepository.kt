package com.robotx.app.arm

import com.robotx.app.arm.model.ArmFrame
import com.robotx.app.arm.model.ArmLinkState
import com.robotx.app.arm.model.ArmLogLine
import com.robotx.app.arm.model.ArmStateDto
import com.robotx.app.arm.model.TempPopup
import com.robotx.app.arm.model.TrajFileDto
import com.robotx.app.arm.transport.ArmEndpoint
import com.robotx.app.arm.transport.ArmTransport
import com.robotx.app.arm.transport.HttpArmApi
import com.robotx.app.arm.transport.WsArmTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `cart_jog` 的 dt 合法区间：服务端要求 `0 < dt ≤ 0.2`，越界整帧被拒。 */
internal const val MIN_JOG_DT = 0.005
internal const val MAX_JOG_DT = 0.2

/**
 * 机械臂控制台的数据中枢。
 *
 * 职责：维持到 arm_web 的 WS 长连（含指数退避重连、2s 心跳）、把下行帧分流成
 * 状态/日志/温度/轨迹四路，并维护本地环形日志。
 * 全部对外只读，写操作统一走 [send]。
 */
object ArmRepository {

    private const val HEARTBEAT_MS = 2_000L
    private const val MIN_RETRY_MS = 1_000L
    private const val MAX_RETRY_MS = 15_000L
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val MAX_LOCAL_LOGS = 200
    private const val TEMP_ALARM_C = 60.0
    private const val TEMP_POPUP_COOLDOWN_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val transport: ArmTransport = WsArmTransport()

    private val _state = MutableStateFlow(ArmStateDto())
    val state: StateFlow<ArmStateDto> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<ArmLogLine>>(emptyList())
    val logs: StateFlow<List<ArmLogLine>> = _logs.asStateFlow()

    private val _tempPopup = MutableStateFlow<TempPopup?>(null)
    val tempPopup: StateFlow<TempPopup?> = _tempPopup.asStateFlow()

    private val _trajFiles = MutableStateFlow<List<TrajFileDto>>(emptyList())
    val trajFiles: StateFlow<List<TrajFileDto>> = _trajFiles.asStateFlow()

    private val _events = MutableSharedFlow<ArmFrame>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 原始帧流（日志/温度/轨迹之外，供后续功能按需订阅）。 */
    val events: SharedFlow<ArmFrame> = _events.asSharedFlow()

    val link: StateFlow<ArmLinkState> = transport.link

    private var endpoint: ArmEndpoint? = null
    private var pendingConnect: JsonObject? = null
    private var autoReconnect = false
    private var retryDelayMs = MIN_RETRY_MS

    private var pumpJob: Job? = null
    private var watchJob: Job? = null
    private var heartbeatJob: Job? = null

    /** 服务端 errors 里的历史行只灌一次，避免 20Hz 状态帧把环形缓冲刷满。 */
    private var logsSeeded = false

    private var lastPopupAtMs = 0L

    /** 上一帧是否有电机处于报警温度，用于识别冷→热上升沿。 */
    private var tempHot = false

    // ------------------------------------------------------------ 连接控制

    /** 发起连接（含自动重连）。重复调用会以新配置重新连。 */
    fun connect(cfg: ArmConfig.Snapshot) {
        if (!cfg.isConfigured) {
            emitLocal("error", "尚未填写机械臂服务地址")
            return
        }
        endpoint = cfg.endpoint()
        pendingConnect = buildJsonObject {
            put("type", "connect")
            put("mode", cfg.mode)
            if (cfg.mode == ArmConfig.Mode.REAL.value && cfg.serialPort.isNotBlank()) {
                put("port", cfg.serialPort)
            }
            put("baudrate", ArmConfig.DEFAULT_BAUDRATE)
            // 两路相机都要下发：只发 camera 的话服务端会把 global 关掉（旧版恒发 0，
            // 而 0 号在本机是「能开、读不到帧」的假设备 → 表现为两个相机都打不开）。
            // CAM_OFF 时**不填**该字段：服务端缺省即「不开这一路」，比塞一个假索引干净。
            if (cfg.camWrist >= 0) put("camera", cfg.camWrist)
            if (cfg.camGlobal >= 0) put("camera2", cfg.camGlobal)
            put("mapping", cfg.mapping.ifBlank { null })
            put("ratio", 1.0)
        }
        _logs.value = emptyList()
        logsSeeded = false
        tempHot = false
        _tempPopup.value = null
        autoReconnect = true
        retryDelayMs = MIN_RETRY_MS
        ensurePump()
        startWatch()
    }

    /** 主动断开，并停止自动重连。 */
    fun disconnect() {
        autoReconnect = false
        watchJob?.cancel()
        watchJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        transport.close()
        _state.value = ArmStateDto()
        emitLocal("info", "已断开")
    }

    /** 发送一帧指令；返回是否写入成功。 */
    fun send(frame: JsonObject): Boolean {
        val ok = transport.send(frame)
        if (!ok) emitLocal("error", "指令未发出（通道未就绪）：${frame["type"]}")
        return ok
    }

    // ------------------------------------------------------------ 运动指令

    /** 使能（kp/kd/window 交给服务端默认值）。 */
    fun enable() {
        send(buildJsonObject { put("type", "enable") })
    }

    /**
     * 失能：**先回零再失能**（服务端 `home_disable`：回起始位姿 → 等各关节
     * 收敛到位（HOME_DISABLE_TOL）或超时 → 才断电）。
     * 直接 `disable` 会让臂在任意位姿掉电、靠重力下坠；回零后的落点是
     * 使能时那个已知/被托住的位姿，更安全。回零期间服务端会自行停掉
     * 回放/示教并退出柔顺与笛卡尔模式，App 无需（也无法）替它编排时序。
     * 急停（[estop]）**不**走这条路：急停必须立即卸力，不等待。
     */
    fun disable() {
        send(buildJsonObject { put("type", "home_disable") })
    }

    /** 急停：服务端立即停止下发并卸力。 */
    fun estop() {
        // 先下发再播报：安全动作不能被 TTS 的初始化/排队拖慢哪怕一帧。
        send(buildJsonObject { put("type", "estop") })
        VoiceManager.speak("急停已触发，机械臂已卸力", urgent = true)
    }

    /**
     * 开/关笛卡尔（前后左右）方向控制。
     *
     * 服务端进门有多条门禁（未使能 / 缺 sign-m0 / 末端在盒外 / 逆解不可用），
     * 拒绝时只写日志不回包 —— 失败原因要靠 [logs] 里的 warn 呈现。
     */
    fun setCartMode(on: Boolean) {
        send(buildJsonObject {
            put("type", "set_cart_mode")
            put("on", on)
        })
    }

    /**
     * 方向点动一帧（按住期间 20Hz）。
     *
     * 方向语义严格照服务端 `_cart_tick`，**不可弄反**：
     * 前 = `ly:-1`（远离基座）、后 = `ly:+1`、左 = `lx:+1`、右 = `lx:-1`；
     * 高度 = `ry` —— 服务端的 Z 增量是 `dp.z = -ry * scale`，
     * 所以 **`ry:+1` = 下降、`ry:-1` = 上升**（与网页版右摇杆「末端升降」同一路）。
     * 姿态轴（rx/hx）本 App 恒为 0。
     *
     * 门禁拒绝时服务端只写日志不回包，所以「推不动」的唯一反馈是日志流。
     */
    fun cartJog(lx: Double, ly: Double, ry: Double, dt: Double, factor: Double) {
        send(buildJsonObject {
            put("type", "cart_jog")
            put("axes", buildJsonObject {
                put("lx", lx)
                put("ly", ly)
                put("rx", 0.0)
                put("ry", ry)
                put("hx", 0.0)
                put("hy", 0.0)
            })
            put("dt", dt.coerceIn(MIN_JOG_DT, MAX_JOG_DT))
            put("factor", factor.coerceIn(0.0, 1.0))
        })
    }

    // ------------------------------------------------------------ 工具指令

    /** 夹爪：`action` = open / close / set（set 必须带 pos）。 */
    fun gripper(action: String, pos: Double? = null) {
        send(buildJsonObject {
            put("type", "gripper")
            put("action", action)
            pos?.let { put("pos", it) }
        })
    }

    /** 示教：`action` = start / pause / resume / mark / stop。 */
    fun record(action: String, label: String = "") {
        send(buildJsonObject {
            put("type", "record")
            put("action", action)
            put("label", label)
        })
    }

    fun replay(file: String, speed: Double = 1.0, loop: Int = 1) {
        send(buildJsonObject {
            put("type", "replay")
            put("file", file)
            put("speed", speed)
            put("loop", loop)
        })
    }

    fun replayStop() {
        send(buildJsonObject { put("type", "replay_stop") })
    }

    /**
     * 拉取轨迹列表并写回 [trajFiles]。
     *
     * 走 HTTP 而不是 WS 的 `list_traj`：后者是「发请求 → 服务端 broadcast」的异步往返，
     * HTTP 一次请求就能拿到，界面不必等推送。
     */
    suspend fun loadTrajList() {
        val ep = endpoint ?: return
        HttpArmApi(ep).trajectories().onSuccess { _trajFiles.value = it }
    }

    // ------------------------------------------------------------ 内部循环

    private fun ensurePump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            transport.events.collect { handleFrame(it) }
        }
    }

    private fun startWatch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive && autoReconnect) {
                val ep = endpoint ?: break
                transport.connect(ep)
                val reached = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    transport.link.first {
                        it == ArmLinkState.ONLINE ||
                            it == ArmLinkState.RETRYING ||
                            it == ArmLinkState.CLOSED
                    }
                }
                if (reached == ArmLinkState.ONLINE) {
                    retryDelayMs = MIN_RETRY_MS
                    onOnline()
                    transport.link.first { it != ArmLinkState.ONLINE }
                    if (autoReconnect) emitLocal("warn", "与服务端的连接断开，正在重连…")
                } else {
                    emitLocal("error", "连接 ${ep.wsUrl()} 失败")
                }
                if (!autoReconnect) break
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    private suspend fun onOnline() {
        pendingConnect?.let { transport.send(it) }
        startHeartbeat()
        emitLocal("info", "已连接服务端，正在下发连接参数…")
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                transport.send(buildJsonObject { put("type", "heartbeat") })
            }
        }
    }

    private fun handleFrame(frame: ArmFrame) {
        _events.tryEmit(frame)
        when (frame) {
            is ArmFrame.State -> onState(frame.value)
            is ArmFrame.Log -> appendLog(frame.value.level, frame.value.msg)
            is ArmFrame.TempAlarm -> onTempAlarm(frame.value.level, frame.value.text, frame.value.ids, frame.value.threshold)
            is ArmFrame.TrajList -> _trajFiles.value = frame.files
            is ArmFrame.Unknown -> Unit
        }
    }

    private fun onState(s: ArmStateDto) {
        _state.value = s
        if (!logsSeeded && s.errors.isNotEmpty()) {
            logsSeeded = true
            val history = s.errors.map { ArmLogLine(level = "history", text = it, atMs = System.currentTimeMillis()) }
            _logs.value = (history + _logs.value).takeLast(MAX_LOCAL_LOGS)
        }
        // 冷 → 热 上升沿：WS 的 temp_alarm 事件偶尔会因重连丢失，这里兜一层。
        val hot = s.joints.any { (it.temp ?: 0.0) >= TEMP_ALARM_C } ||
            (s.gripper?.temp ?: 0.0) >= TEMP_ALARM_C
        if (hot && !tempHot) {
            val ids = s.joints.filter { (it.temp ?: 0.0) >= TEMP_ALARM_C }.map { it.id }
            onTempAlarm("alarm", "电机温度超过 ${TEMP_ALARM_C.toInt()}℃，请降低负载或暂停使用", ids, TEMP_ALARM_C)
        }
        tempHot = hot
    }

    private fun onTempAlarm(level: String, text: String, ids: List<Int>, threshold: Double) {
        if (level != "alarm") return
        val now = System.currentTimeMillis()
        if (now - lastPopupAtMs < TEMP_POPUP_COOLDOWN_MS) return
        lastPopupAtMs = now
        _tempPopup.value = TempPopup(level = level, text = text, ids = ids, threshold = threshold)
        // 温度报警只闪不响很容易漏 —— 操作者的眼睛多半在机械臂上，不在屏幕上。
        // 20s 冷却已经在上面挡过，所以这里不会变成复读机。
        VoiceManager.speak("警告，电机温度过高，请降低负载或暂停使用", urgent = true)
    }

    /** UI 在弹窗展示 5s 后调用。 */
    fun dismissTempPopup() {
        _tempPopup.value = null
    }

    private fun appendLog(level: String, msg: String) {
        val line = ArmLogLine(level = level, text = "${fmtTime(System.currentTimeMillis())}  ${msg}", atMs = System.currentTimeMillis())
        _logs.value = (_logs.value + line).takeLast(MAX_LOCAL_LOGS)
    }

    private fun emitLocal(level: String, msg: String) = appendLog(level, msg)

    private fun fmtTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FMT)

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
}