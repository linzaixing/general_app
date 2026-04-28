package com.robotx.app.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

private val Context.apiDataStore: DataStore<Preferences> by preferencesDataStore(name = "robotx_api_config")

/**
 * Holds runtime-mutable configuration for the cloud LLM endpoints.
 *
 * Fields are exposed both as a StateFlow (for Compose) and a snapshot value (for synchronous
 * access from network code). All writes are persisted via Jetpack DataStore.
 */
object ApiConfig {

    data class Snapshot(
        val baseUrl: String,
        val apiKey: String,
        val asrPath: String,
        val asrModel: String,
        val vlaPath: String,
        val vlaModel: String,
    ) {
        val isConfigured: Boolean
            get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

        fun resolvedAsrUrl(): String = joinUrl(baseUrl, asrPath)
        fun resolvedVlaUrl(): String = joinUrl(baseUrl, vlaPath)
    }

    private val KEY_BASE_URL = stringPreferencesKey("base_url")
    private val KEY_API_KEY = stringPreferencesKey("api_key")
    private val KEY_ASR_PATH = stringPreferencesKey("asr_path")
    private val KEY_ASR_MODEL = stringPreferencesKey("asr_model")
    private val KEY_VLA_PATH = stringPreferencesKey("vla_path")
    private val KEY_VLA_MODEL = stringPreferencesKey("vla_model")

    // 默认直接对接阿里云 DashScope OpenAI 兼容协议，开箱即用。
    private const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private const val DEFAULT_API_KEY = "sk-f2f9a568b36946d8ad4eb777bfcaa6a6"
    private const val DEFAULT_ASR_PATH = "/chat/completions"
    private const val DEFAULT_ASR_MODEL = "qwen3-asr-flash"
    private const val DEFAULT_VLA_PATH = "/chat/completions"
    private const val DEFAULT_VLA_MODEL = "qwen2.5-vl-7b-instruct"

    /** 预设：可在 SettingsDialog 里点按钮一键填入。 */
    enum class Preset(
        val displayName: String,
        val baseUrl: String,
        val asrPath: String,
        val asrModel: String,
        val vlaPath: String,
        val vlaModel: String,
    ) {
        SELF_HOSTED(
            "自建服务器",
            "http://192.168.1.100:8000/v1",
            "/audio/transcriptions", "whisper-1",
            "/chat/completions", "qwen2.5-vl-7b-instruct",
        ),
        DASHSCOPE(
            "阿里云 DashScope",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            // DashScope OpenAI 兼容 ASR 走 chat/completions + input_audio
            "/chat/completions", "qwen3-asr-flash",
            "/chat/completions", "qwen2.5-vl-7b-instruct",
        ),
    }

    suspend fun applyPreset(preset: Preset, keepApiKey: Boolean = true) {
        update(
            baseUrl = preset.baseUrl,
            apiKey = if (keepApiKey) null else "",
            asrPath = preset.asrPath,
            asrModel = preset.asrModel,
            vlaPath = preset.vlaPath,
            vlaModel = preset.vlaModel,
        )
    }

    private lateinit var appContext: Context
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        Snapshot(
            baseUrl = DEFAULT_BASE_URL,
            apiKey = DEFAULT_API_KEY,
            asrPath = DEFAULT_ASR_PATH,
            asrModel = DEFAULT_ASR_MODEL,
            vlaPath = DEFAULT_VLA_PATH,
            vlaModel = DEFAULT_VLA_MODEL,
        )
    )
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        // Synchronously load the first emission so that snapshot() is usable
        // from non-suspending callers (e.g. network code) right after init.
        runBlocking {
            val p = appContext.apiDataStore.data.first()
            _state.value = snapshotFromPrefs(p)
        }
        ioScope.launch {
            appContext.apiDataStore.data.collect { p ->
                _state.value = snapshotFromPrefs(p)
            }
        }
    }

    fun snapshot(): Snapshot = _state.value

    fun observe(): Flow<Snapshot> = _state

    suspend fun update(
        baseUrl: String? = null,
        apiKey: String? = null,
        asrPath: String? = null,
        asrModel: String? = null,
        vlaPath: String? = null,
        vlaModel: String? = null,
    ) {
        appContext.apiDataStore.edit { prefs ->
            baseUrl?.let { prefs[KEY_BASE_URL] = it.trim() }
            apiKey?.let { prefs[KEY_API_KEY] = it.trim() }
            asrPath?.let { prefs[KEY_ASR_PATH] = it.trim().ifEmpty { DEFAULT_ASR_PATH } }
            asrModel?.let { prefs[KEY_ASR_MODEL] = it.trim().ifEmpty { DEFAULT_ASR_MODEL } }
            vlaPath?.let { prefs[KEY_VLA_PATH] = it.trim().ifEmpty { DEFAULT_VLA_PATH } }
            vlaModel?.let { prefs[KEY_VLA_MODEL] = it.trim().ifEmpty { DEFAULT_VLA_MODEL } }
        }
    }

    private fun joinUrl(base: String, path: String): String {
        val b = base.trim().trimEnd('/')
        val cleanPath = path.trim()
        val p = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
        return b + p
    }

    /**
     * 把 DataStore 里的 Preferences 还原成 Snapshot：
     * - 字段缺失或被存为空字符串时，全部回填默认值（默认指向阿里云 DashScope）。
     * - 这样老安装（曾把 apiKey 存为空）重启后也能拿到内置的默认 Key。
     */
    private fun snapshotFromPrefs(p: Preferences): Snapshot = Snapshot(
        baseUrl = p[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
        apiKey = p[KEY_API_KEY]?.takeIf { it.isNotBlank() } ?: DEFAULT_API_KEY,
        asrPath = p[KEY_ASR_PATH]?.takeIf { it.isNotBlank() } ?: DEFAULT_ASR_PATH,
        asrModel = p[KEY_ASR_MODEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_ASR_MODEL,
        vlaPath = p[KEY_VLA_PATH]?.takeIf { it.isNotBlank() } ?: DEFAULT_VLA_PATH,
        vlaModel = p[KEY_VLA_MODEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_VLA_MODEL,
    )
}
