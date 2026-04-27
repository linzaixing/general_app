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

    // 默认指向占位的自建服务器 IP，请到设置里改成自己的服务器地址或选预设。
    private const val DEFAULT_BASE_URL = "http://192.168.1.100:8000/v1"
    private const val DEFAULT_ASR_PATH = "/audio/transcriptions"
    private const val DEFAULT_ASR_MODEL = "whisper-1"
    private const val DEFAULT_VLA_PATH = "/chat/completions"
    private const val DEFAULT_VLA_MODEL = "gpt-4o-mini"

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
            "/chat/completions", "gpt-4o-mini",
        ),
        DASHSCOPE(
            "阿里云 DashScope",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            // DashScope OpenAI 兼容 ASR 走 chat/completions + input_audio
            "/chat/completions", "qwen3-asr-flash",
            "/chat/completions", "qwen2.5-vl-7b-instruct",
        ),
        OPENAI(
            "OpenAI",
            "https://api.openai.com/v1",
            "/audio/transcriptions", "whisper-1",
            "/chat/completions", "gpt-4o-mini",
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
            baseUrl = "",
            apiKey = "",
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
            _state.value = Snapshot(
                baseUrl = p[KEY_BASE_URL] ?: DEFAULT_BASE_URL,
                apiKey = p[KEY_API_KEY] ?: "",
                asrPath = p[KEY_ASR_PATH] ?: DEFAULT_ASR_PATH,
                asrModel = p[KEY_ASR_MODEL] ?: DEFAULT_ASR_MODEL,
                vlaPath = p[KEY_VLA_PATH] ?: DEFAULT_VLA_PATH,
                vlaModel = p[KEY_VLA_MODEL] ?: DEFAULT_VLA_MODEL,
            )
        }
        ioScope.launch {
            appContext.apiDataStore.data.collect { p ->
                _state.value = Snapshot(
                    baseUrl = p[KEY_BASE_URL] ?: DEFAULT_BASE_URL,
                    apiKey = p[KEY_API_KEY] ?: "",
                    asrPath = p[KEY_ASR_PATH] ?: DEFAULT_ASR_PATH,
                    asrModel = p[KEY_ASR_MODEL] ?: DEFAULT_ASR_MODEL,
                    vlaPath = p[KEY_VLA_PATH] ?: DEFAULT_VLA_PATH,
                    vlaModel = p[KEY_VLA_MODEL] ?: DEFAULT_VLA_MODEL,
                )
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
}
