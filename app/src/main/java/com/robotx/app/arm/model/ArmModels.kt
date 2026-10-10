package com.robotx.app.arm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * arm_web 服务端推送的数据模型（见 `arm_web/app.py` 的 `snapshot()` / `broadcast()`）。
 *
 * 所有字段都给默认值：服务端版本变化新增/缺失键时不会反序列化失败。
 * 数值统一用 Double?，因为服务端在读数缺失时会发 `null`。
 */

@Serializable
data class JointDto(
    val id: Int = 0,
    val name: String = "",
    /** 电机读数（rad），不是 URDF 关节角。 */
    val pos: Double? = null,
    /** 只读派生量：URDF 关节角 = sign×(pos−m0)，3D 视窗用。缺映射时为 null。 */
    val q: Double? = null,
    /**
     * 只读派生量：相对**用户软件零位**的关节角（rad），与 [q] 并列。
     * 本 App 的 3D 仿真（FK）继续用 [q]；若 UI 要展示「用户视角」的关节角，
     * 取 [qz]（服务端未上报时回退 [q]）。
     */
    val qz: Double? = null,
    val vel: Double? = null,
    val torque: Double? = null,
    val temp: Double? = null,
    val target: Double? = null,
    val lo: Double? = null,
    val hi: Double? = null,
    val issues: List<String> = emptyList(),
    val faults: List<JsonElement> = emptyList(),
)

@Serializable
data class GripperDto(
    val id: Int = 0,
    val pos: Double? = null,
    val target: Double? = null,
    val torque: Double? = null,
    val temp: Double? = null,
    val issues: List<String> = emptyList(),
    val faults: List<JsonElement> = emptyList(),
    val lo: Double? = null,
    val hi: Double? = null,
)

@Serializable
data class CameraInfoDto(
    val available: Boolean = false,
    val index: Int? = null,
    val width: Int = 0,
    val height: Int = 0,
    val msg: String = "",
)

@Serializable
data class CameraDto(
    val available: Boolean = false,
    val index: Int? = null,
    val width: Int = 0,
    val height: Int = 0,
    val msg: String = "",
    val wrist: CameraInfoDto = CameraInfoDto(),
    val global: CameraInfoDto = CameraInfoDto(),
)

@Serializable
data class PushDto(
    val frames: Long = 0,
    val errors: Long = 0,
    val loopErrors: Long = 0,
    @SerialName("age") val age: Double? = null,
)

@Serializable
data class RecDto(
    val state: String = "idle",
    val n: Int = 0,
    val duration: Double = 0.0,
    val notes: String = "",
)

@Serializable
data class ArmStateDto(
    val type: String = "state",
    val connected: Boolean = false,
    val mode: String = "none",
    val enabled: Boolean = false,
    val running: Boolean = false,
    @SerialName("loop_count") val loopCount: Int = 0,
    @SerialName("loop_hz") val loopHz: Double? = null,
    @SerialName("heartbeat_age") val heartbeatAge: Double? = null,
    val compliant: Boolean = false,
    @SerialName("cart_mode") val cartMode: Boolean = false,
    val teaching: String = "idle",
    val window: Double? = null,
    val kp: Double? = null,
    val kd: Double? = null,
    val joints: List<JointDto> = emptyList(),
    val gripper: GripperDto? = null,
    /** 服务端最近 40 条日志，纯文本行。 */
    val errors: List<String> = emptyList(),
    val rec: RecDto = RecDto(),
    val camera: CameraDto = CameraDto(),
    val push: PushDto = PushDto(),
)

@Serializable
data class LogLineDto(
    val type: String = "log",
    val level: String = "info",
    val msg: String = "",
)

@Serializable
data class TempAlarmDto(
    val type: String = "temp_alarm",
    val level: String = "alarm",
    val threshold: Double = 60.0,
    val ids: List<Int> = emptyList(),
    /** 键是电机 ID 字符串（服务端 json 对象的键只能是字符串）。 */
    val temps: Map<String, Double> = emptyMap(),
    val text: String = "",
)

@Serializable
data class TrajFileDto(
    val name: String = "",
    val created: String = "",
    val n: Int = 0,
    val ids: List<Int> = emptyList(),
    val notes: String = "",
)

/** 服务端未知/暂未建模的消息，原样保留以便排查。 */
data class UnknownFrame(val raw: JsonObject)

// ------------------------------------------------------------ HTTP 接口模型

/** `/api/trajectories` 的响应包。 */
@Serializable
data class TrajListEnvelope(val files: List<TrajFileDto> = emptyList(), val msg: String = "")

/**
 * `POST /api/voice_command` 的响应。
 *
 * `intent` 是服务端 LLM 解出来的意图对象（结构由 `arm_control.voice` 决定），
 * 这里不建模、原样展示给用户看，避免服务端一改结构 App 就崩。
 */
@Serializable
data class VoiceResultDto(
    val ok: Boolean = false,
    val transcript: String = "",
    val intent: JsonObject? = null,
    val reply: String = "",
    val executed: Boolean = false,
    @SerialName("dry_run") val dryRun: Boolean = false,
)

/** `GET /api/vision/detect` 的响应；`found=false` 是正常结果，不是错误。 */
@Serializable
data class VisionDetectDto(
    val ok: Boolean = false,
    val cam: String = "",
    val preset: String = "",
    val found: Boolean = false,
    val cx: Double? = null,
    val cy: Double? = null,
    /** 服务端在未检出时给 null，所以这里必须是可空。 */
    val bbox: List<Double>? = null,
    val area: Double? = null,
    val ms: Double? = null,
    val msg: String = "",
)

/** `POST /api/vision/grab` 的响应；始终 HTTP 200，成败看 `ok`。 */
@Serializable
data class VisionGrabDto(
    val ok: Boolean = false,
    val reason: String = "",
    val cam: String = "",
    val rounds: Int = 0,
    @SerialName("err_px") val errPx: Double? = null,
    val converged: Boolean = false,
    @SerialName("descended_m") val descendedM: Double? = null,
    val closed: Boolean = false,
    val travel: Double? = null,
    val ms: Double? = null,
    val log: List<String> = emptyList(),
)

/** WS 下行的一帧：先按顶层 `type` 分流，再各自 decode。 */
sealed interface ArmFrame {
    data class State(val value: ArmStateDto) : ArmFrame
    data class Log(val value: LogLineDto) : ArmFrame
    data class TempAlarm(val value: TempAlarmDto) : ArmFrame
    data class TrajList(val files: List<TrajFileDto>) : ArmFrame
    data class Unknown(val value: UnknownFrame) : ArmFrame
}

/**
 * 本机记录的一条日志（服务端的 `errors` 行 + WS 的 `log` 事件合流）。
 */
data class ArmLogLine(
    val level: String,
    val text: String,
    val atMs: Long,
)

/** 连接状态机。 */
enum class ArmLinkState { IDLE, CONNECTING, ONLINE, RETRYING, CLOSED }

/** 温度弹窗请求（20s 冷却在 [com.robotx.app.arm.ArmRepository] 内做）。 */
data class TempPopup(val level: String, val text: String, val ids: List<Int>, val threshold: Double)