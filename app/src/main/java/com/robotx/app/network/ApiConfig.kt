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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.apiDataStore: DataStore<Preferences> by preferencesDataStore(name = "robotx_api_config")

/**
 * 模型侧配置：只剩「用哪个 Key、用哪个模型」。
 *
 * 请求地址由服务端自己决定，App 不需要知道 —— 原先那套 Base URL / ASR 路径 / VLA 路径
 * 的「云端 API 配置」已随设置页重构删除。
 */
object ApiConfig {

    data class Snapshot(
        val apiKey: String,
        /** `/api/voice_command` 的 `asr_model`：语音 → 文字。 */
        val asrModel: String,
        /** `/api/voice_command` 的 `chat_model`：文字 → 意图（视觉抓取决策也走它）。 */
        val chatModel: String,
    )

    private val KEY_API_KEY = stringPreferencesKey("api_key")
    private val KEY_ASR_MODEL = stringPreferencesKey("asr_model")

    // 键名沿用历史的 "vla_model"：老安装里已存在的值不会被丢。
    private val KEY_CHAT_MODEL = stringPreferencesKey("vla_model")

    private const val DEFAULT_API_KEY = "sk-f2f9a568b36946d8ad4eb777bfcaa6a6"
    private const val DEFAULT_ASR_MODEL = "qwen3-asr-flash"
    private const val DEFAULT_CHAT_MODEL = "qwen2.5-vl-7b-instruct"

    /** 设置页给用户直接点的候选模型（也可以自己手填）。 */
    val ASR_MODEL_CHOICES = listOf("qwen3-asr-flash", "qwen-audio-asr")
    val CHAT_MODEL_CHOICES = listOf("qwen2.5-vl-7b-instruct", "qwen-vl-max", "qwen-vl-plus")

    private lateinit var appContext: Context
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        Snapshot(
            apiKey = DEFAULT_API_KEY,
            asrModel = DEFAULT_ASR_MODEL,
            chatModel = DEFAULT_CHAT_MODEL,
        )
    )
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        // 同步读一次首帧，让非挂起调用方（网络层）在 init 后立刻能拿到 snapshot()。
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

    suspend fun update(apiKey: String? = null, asrModel: String? = null, chatModel: String? = null) {
        appContext.apiDataStore.edit { prefs ->
            apiKey?.let { prefs[KEY_API_KEY] = it.trim() }
            asrModel?.let { prefs[KEY_ASR_MODEL] = it.trim().ifEmpty { DEFAULT_ASR_MODEL } }
            chatModel?.let { prefs[KEY_CHAT_MODEL] = it.trim().ifEmpty { DEFAULT_CHAT_MODEL } }
        }
    }

    /** 字段缺失/被存空时一律回填默认值，保证老安装升级后仍可用。 */
    private fun snapshotFromPrefs(p: Preferences): Snapshot = Snapshot(
        apiKey = p[KEY_API_KEY]?.takeIf { it.isNotBlank() } ?: DEFAULT_API_KEY,
        asrModel = p[KEY_ASR_MODEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_ASR_MODEL,
        chatModel = p[KEY_CHAT_MODEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT_MODEL,
    )
}