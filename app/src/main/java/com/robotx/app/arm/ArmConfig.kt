package com.robotx.app.arm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.robotx.app.arm.transport.ArmEndpoint

private val Context.armDataStore: DataStore<Preferences> by preferencesDataStore(name = "robotx_arm_config")

/**
 * 机械臂控制台（arm_web 服务）的连接配置。
 *
 * 与 [com.robotx.app.network.ApiConfig] 刻意分开：那是云端大模型网关的配置，
 * 这里是局域网机械臂服务，两者的 host/端口/生命周期都不一样，共用会互相污染。
 */
object ArmConfig {

    data class Snapshot(
        val host: String,
        val port: Int,
        val mode: String,
        val serialPort: String,
        val mapping: String,
        val camWrist: Int,
        val camGlobal: Int,
    ) {
        /** 是否已填了可用的主机地址（IP 或域名）。 */
        val isConfigured: Boolean get() = host.isNotBlank()

        /**
         * 构造传输端点。将来换公网 IP / 蓝牙时，只改这里的构造来源，
         * 上层 UI 与 Repository 不受影响。
         */
        fun endpoint(): ArmEndpoint = ArmEndpoint(host = stripScheme(host), port = port)
    }

    enum class Mode(val value: String, val label: String) {
        SIM("sim", "仿真"),
        REAL("real", "真机"),
    }

    private val KEY_HOST = stringPreferencesKey("arm_host")
    private val KEY_PORT = intPreferencesKey("arm_port")
    private val KEY_MODE = stringPreferencesKey("arm_mode")
    private val KEY_SERIAL_PORT = stringPreferencesKey("arm_serial_port")
    private val KEY_MAPPING = stringPreferencesKey("arm_mapping")
    private val KEY_CAM_WRIST = intPreferencesKey("arm_cam_wrist")
    private val KEY_CAM_GLOBAL = intPreferencesKey("arm_cam_global")

    const val DEFAULT_PORT = 8010
    const val DEFAULT_BAUDRATE = 921600
    private const val DEFAULT_MODE = "sim"

    /**
     * 默认映射文件：服务端本机已辨识好的 `sign_mapping.json`（相对服务端工作目录）。
     *
     * ⚠️ 它同时被三处读：① 笛卡尔（逆运动学）逆解需要它才能进门；
     * ② 3D 视窗的关节角 `q = sign×(pos−m0)`（正运动学展示）也由服务端用同一份算；
     * ③ 重力前馈。**留空会让笛卡尔被永久拒绝**（服务端只用占位值 1/0/1）。
     * 所以这里默认就填上，保证正/逆运动学用的是同一份标定。
     */
    const val DEFAULT_MAPPING = "arm_control/sign_mapping.json"

    /**
     * 相机设备号：**必须填「活」的真相机索引**，否则服务端每次抓图都要走一遍
     * 阻塞的失败重试，会把同进程的状态推送 / 指令链路拖慢（真机表现为关节运动发顿）。
     *
     * ⚠️ 2026-09-29 实测修正：`enumerate_cameras(warmup=True)` 报的 `readable=true`
     * **只说明「能读到一帧」，不代表是活的相机** —— 本机 #4 也能读，但连续 6 帧字节
     * **完全相同**（NV12 静态画面，Windows「相机已关闭」占位图），是虚拟/占位设备；
     * #0 则是「能打开、读不到帧」，一次失败重试就要 39 s。
     * 判活要用「连续多帧是否变化」，本机只有 **#1（YUY2）** 是真的。
     */
    const val DEFAULT_CAM_WRIST = 1

    /**
     * **不打开该路相机**的哨兵值。留空即此值 —— 服务端 `camera2` 缺省时会主动
     * `close_camera("global")`，前端会明确显示「该路未打开」，而不是塞一张假的静态画面。
     * 真实索引是 `0..MAX_CAM_INDEX`，所以 `-1` 不会与任何设备号冲突。
     */
    const val CAM_OFF = -1

    /** 全局相机默认**不开**：本机当前只有 #1 一个真相机，硬填 4 只会得到一张假的占位图。 */
    const val DEFAULT_CAM_GLOBAL = CAM_OFF

    const val MAX_CAM_INDEX = 9

    private lateinit var appContext: Context
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        Snapshot(
            host = "",
            port = DEFAULT_PORT,
            mode = DEFAULT_MODE,
            serialPort = "",
            mapping = DEFAULT_MAPPING,
            camWrist = DEFAULT_CAM_WRIST,
            camGlobal = DEFAULT_CAM_GLOBAL,
        )
    )
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        // 同步取首帧，保证 init 之后 snapshot() 立刻可用（网络层是同步调用的）。
        runBlocking {
            _state.value = snapshotFromPrefs(appContext.armDataStore.data.first())
        }
        ioScope.launch {
            appContext.armDataStore.data.collect { p -> _state.value = snapshotFromPrefs(p) }
        }
    }

    fun snapshot(): Snapshot = _state.value

    fun observe(): Flow<Snapshot> = _state

    suspend fun update(
        host: String? = null,
        port: Int? = null,
        mode: String? = null,
        serialPort: String? = null,
        mapping: String? = null,
        camWrist: Int? = null,
        camGlobal: Int? = null,
    ) {
        appContext.armDataStore.edit { prefs ->
            host?.let { prefs[KEY_HOST] = stripScheme(it) }
            port?.let { prefs[KEY_PORT] = if (it in 1..65535) it else DEFAULT_PORT }
            mode?.let { prefs[KEY_MODE] = it.trim().ifEmpty { DEFAULT_MODE } }
            serialPort?.let { prefs[KEY_SERIAL_PORT] = it.trim() }
            mapping?.let { prefs[KEY_MAPPING] = it.trim() }
            camWrist?.let { prefs[KEY_CAM_WRIST] = it.coerceIn(CAM_OFF, MAX_CAM_INDEX) }
            camGlobal?.let { prefs[KEY_CAM_GLOBAL] = it.coerceIn(CAM_OFF, MAX_CAM_INDEX) }
        }
    }

    /** 去掉 `http://` / `ws://` 之类前缀、尾部斜杠与路径，只留 host[:port]。 */
    private fun stripScheme(raw: String): String {
        var s = raw.trim()
        for (p in SCHEMES) {
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.substring(p.length)
                break
            }
        }
        return s.substringBefore('/').trim()
    }

    private val SCHEMES = listOf("http://", "https://", "ws://", "wss://")

    private fun snapshotFromPrefs(p: Preferences): Snapshot = Snapshot(
        host = p[KEY_HOST]?.let { stripScheme(it) } ?: "",
        port = p[KEY_PORT] ?: DEFAULT_PORT,
        mode = p[KEY_MODE]?.takeIf { it.isNotBlank() } ?: DEFAULT_MODE,
        serialPort = p[KEY_SERIAL_PORT] ?: "",
        mapping = p[KEY_MAPPING]?.takeIf { it.isNotBlank() } ?: DEFAULT_MAPPING,
        camWrist = (p[KEY_CAM_WRIST] ?: DEFAULT_CAM_WRIST).coerceIn(CAM_OFF, MAX_CAM_INDEX),
        camGlobal = (p[KEY_CAM_GLOBAL] ?: DEFAULT_CAM_GLOBAL).coerceIn(CAM_OFF, MAX_CAM_INDEX),
    )
}