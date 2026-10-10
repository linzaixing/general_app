package com.robotx.app.arm.transport

import com.robotx.app.arm.model.ArmStateDto
import com.robotx.app.arm.model.TrajFileDto
import com.robotx.app.arm.model.TrajListEnvelope
import com.robotx.app.arm.model.VisionDetectDto
import com.robotx.app.arm.model.VisionGrabDto
import com.robotx.app.arm.model.VoiceResultDto
import com.robotx.app.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * arm_web 的只读/低频 HTTP 接口（`/api/` 前缀）。
 *
 * 复用 [HttpClient.client]（短请求，超时策略合适）；MJPEG 长流以后单独用
 * `readTimeout(0)` 的客户端，不走这里。
 */
class HttpArmApi(private val endpoint: ArmEndpoint) {

    @Serializable
    data class SerialPortDto(val device: String = "", val description: String = "")

    @Serializable
    private data class SerialPortsEnvelope(val ports: List<SerialPortDto> = emptyList(), val msg: String = "")

    suspend fun fetchState(): Result<ArmStateDto> = get(
        endpoint.apiUrl("/api/state"),
        ArmStateDto.serializer(),
    )

    /** 扫描可用串口，供连接页的下拉列表使用。 */
    suspend fun serialPorts(): Result<List<SerialPortDto>> = get(
        endpoint.apiUrl("/api/serial/ports"),
        SerialPortsEnvelope.serializer(),
    ).map { it.ports }

    /** 历史轨迹文件列表（与 WS 的 `list_traj` 同一份数据，HTTP 版更省事）。 */
    suspend fun trajectories(): Result<List<TrajFileDto>> = get(
        endpoint.apiUrl("/api/trajectories"),
        TrajListEnvelope.serializer(),
    ).map { it.files }

    /**
     * 抓一帧 JPEG（`?cam=wrist|global`）；失败返回 null。
     *
     * 相机没打开时服务端会回一张占位图，所以拿到图片≠有真相机。
     */
    suspend fun snapshotJpeg(cam: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(endpoint.apiUrl("/api/camera/snapshot?cam=$cam"))
                .get()
                .build()
            HttpClient.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    /**
     * 语音 → 意图 →（[dryRun] 为 false 时）安全执行。
     *
     * 两种入参二选一，服务端两种都收：
     * - [audioB64]：16kHz 单声道 WAV 的 Base64，服务端负责 ASR（[asrModel] 指定模型）；
     * - [text]：已经识别好的文本，用于第二轮执行时复用第一轮的 transcript，不重复跑 ASR。
     *
     * 服务端在未连接机械臂或缺 api_key 时会返回 `ok=false` + `reply` 说明原因，
     * 所以这里不把 `ok=false` 当接口失败；只有网络/解码异常才走 [Result.failure]。
     */
    suspend fun voiceCommand(
        apiKey: String,
        cam: String,
        dryRun: Boolean,
        text: String? = null,
        audioB64: String? = null,
        asrModel: String? = null,
        chatModel: String? = null,
    ): Result<VoiceResultDto> = postJson(
        endpoint.apiUrl("/api/voice_command"),
        buildJsonObject {
            text?.takeIf { it.isNotBlank() }?.let { put("text", it) }
            audioB64?.takeIf { it.isNotBlank() }?.let { put("audio_b64", it) }
            put("api_key", apiKey)
            asrModel?.takeIf { it.isNotBlank() }?.let { put("asr_model", it) }
            chatModel?.takeIf { it.isNotBlank() }?.let { put("chat_model", it) }
            put("cam", cam)
            put("dry_run", dryRun)
        },
        VoiceResultDto.serializer(),
    )

    /** 颜色检测：**只返回结果，绝不动作**（要动必须再调 [visionGrab]）。 */
    suspend fun visionDetect(cam: String, preset: String): Result<VisionDetectDto> = get(
        endpoint.apiUrl("/api/vision/detect?cam=$cam&preset=$preset"),
        VisionDetectDto.serializer(),
    )

    /** 视觉伺服抓取：**会真的动机械臂**，调用前必须由用户二次确认。 */
    suspend fun visionGrab(
        cam: String,
        preset: String,
        tx: Double? = null,
        ty: Double? = null,
        descendM: Double? = null,
        close: Boolean? = null,
    ): Result<VisionGrabDto> = postJson(
        endpoint.apiUrl("/api/vision/grab"),
        buildJsonObject {
            put("cam", cam)
            put("preset", preset)
            tx?.let { put("tx", it) }
            ty?.let { put("ty", it) }
            descendM?.let { put("descend_m", it) }
            close?.let { put("close", it) }
        },
        VisionGrabDto.serializer(),
    )

    private suspend fun <T> get(url: String, serializer: KSerializer<T>): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).get().build()
                HttpClient.client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "HTTP ${resp.code}: ${body.take(200)}" }
                    HttpClient.json.decodeFromString(serializer, body)
                }
            }
        }

    private suspend fun <T> postJson(
        url: String,
        body: JsonObject,
        serializer: KSerializer<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            HttpClient.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "HTTP ${resp.code}: ${text.take(200)}" }
                HttpClient.json.decodeFromString(serializer, text)
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}