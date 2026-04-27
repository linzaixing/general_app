package com.robotx.app.audio

import com.robotx.app.network.ApiConfig
import com.robotx.app.network.HttpClient
import com.robotx.app.network.dto.AsrResponse
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Uploads an audio file to the configured cloud ASR endpoint and returns
 * the recognised text. Default protocol is OpenAI compatible:
 *
 *   POST {baseUrl}{asrPath}
 *   Authorization: Bearer <key>
 *   multipart/form-data:
 *     - file:     <audio.wav>     (OpenAI 标准字段名)
 *     - audio:    <audio.wav>     (一些自建/中文 ASR 服务用这个名字，重复发以兼容)
 *     - model:    <asrModel>
 *     - language: zh
 *     - response_format: json
 *
 * 错误时抛出包含 URL / HTTP code / 响应正文片段的 IllegalStateException，
 * 方便排查 404 等问题。
 */
object AsrClient {

    private val WAV = "audio/wav".toMediaType()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun transcribe(audio: File): Result<String> = withContext(Dispatchers.IO) {
        val cfg = ApiConfig.snapshot()
        if (!cfg.isConfigured) {
            return@withContext Result.failure(IllegalStateException("尚未配置 Base URL 或 API Key"))
        }
        if (!audio.exists() || audio.length() < 1024) {
            return@withContext Result.failure(IllegalStateException("录音过短"))
        }

        val asrUrl = cfg.resolvedAsrUrl()

        // DashScope OpenAI-compatible ASR uses chat/completions + input_audio
        if (isDashScopeCompat(cfg)) {
            val dashUrl = if (cfg.asrPath.lowercase().contains("chat/completions")) {
                asrUrl
            } else {
                // Backward compatibility for old saved config `/audio/transcriptions`
                cfg.resolvedVlaUrl()
            }
            return@withContext transcribeByDashScopeCompat(audio, cfg, dashUrl)
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", cfg.asrModel)
            .addFormDataPart("language", "zh")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", audio.name, audio.asRequestBody(WAV))
            .addFormDataPart("audio", audio.name, audio.asRequestBody(WAV))
            .build()

        val builder = Request.Builder()
            .url(asrUrl)
            .header("Accept", "application/json")
            .post(body)
        if (cfg.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${cfg.apiKey}")
        }
        val req = builder.build()

        runCatching { execute(req, asrUrl) }
            .mapCatching { raw ->
                val resp = HttpClient.json.decodeFromString(AsrResponse.serializer(), raw)
                val text = resp.pickText()
                if (text.isBlank()) {
                    throw IllegalStateException(
                        resp.message ?: "ASR 返回为空 (URL=$asrUrl body=${raw.take(200)})"
                    )
                }
                text
            }
    }

    private suspend fun transcribeByDashScopeCompat(
        audio: File,
        cfg: ApiConfig.Snapshot,
        url: String,
    ): Result<String> {
        val dataUrl = "data:audio/wav;base64," + Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
        val bodyJson = buildJsonObject {
            val dashAsrModel = if (cfg.asrModel.lowercase().contains("qwen3-asr")) {
                cfg.asrModel
            } else {
                "qwen3-asr-flash"
            }
            put("model", dashAsrModel)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "input_audio")
                                            put(
                                                "input_audio",
                                                buildJsonObject { put("data", dataUrl) }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            put("stream", false)
            put(
                "asr_options",
                buildJsonObject {
                    put("language", "zh")
                    put("enable_itn", true)
                }
            )
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(HttpClient.json.encodeToString(JsonObject.serializer(), bodyJson).toRequestBody(JSON))
        if (cfg.apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${cfg.apiKey}")
        }

        return runCatching { execute(reqBuilder.build(), url) }
            .mapCatching { raw ->
                val text = extractChatCompletionText(raw)
                if (text.isBlank()) {
                    throw IllegalStateException("DashScope ASR 返回为空 (URL=$url body=${raw.take(300)})")
                }
                text
            }
    }

    private fun isDashScopeCompat(cfg: ApiConfig.Snapshot): Boolean {
        val base = cfg.baseUrl.lowercase()
        val model = cfg.asrModel.lowercase()
        val path = cfg.asrPath.lowercase()
        return base.contains("dashscope.aliyuncs.com") ||
            base.contains("dashscope-intl.aliyuncs.com") ||
            model.contains("qwen3-asr") ||
            path.contains("chat/completions")
    }

    private fun extractChatCompletionText(raw: String): String {
        val root = HttpClient.json.parseToJsonElement(raw) as? JsonObject ?: return ""
        val choices = root["choices"] as? JsonArray ?: return ""
        val first = choices.firstOrNull() as? JsonObject ?: return ""
        val msg = first["message"] as? JsonObject ?: return ""
        val content = msg["content"] ?: return ""
        return when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.joinToString(separator = "") { part ->
                val obj = part as? JsonObject ?: return@joinToString ""
                (obj["text"] as? JsonPrimitive)?.content.orEmpty()
            }
            else -> ""
        }.trim()
    }

    private suspend fun execute(req: Request, url: String): String =
        suspendCancellableCoroutine { cont ->
            val call = HttpClient.client.newCall(req)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(
                        IOException("网络错误 (URL=$url): ${e.message}", e)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) {
                            val hint = when (r.code) {
                                404 -> "服务器没有这个接口路径，请检查 ASR 路径（阿里云建议使用 /chat/completions）"
                                401, 403 -> "鉴权失败，请检查 API Key"
                                413 -> "音频太大被服务器拒绝"
                                else -> ""
                            }
                            cont.resumeWithException(
                                IOException(
                                    "HTTP ${r.code} ${r.message} ${if (hint.isNotEmpty()) "(${hint})" else ""}\n" +
                                            "URL=$url\n响应=${body.take(300)}"
                                )
                            )
                        } else {
                            cont.resume(body)
                        }
                    }
                }
            })
        }
}
