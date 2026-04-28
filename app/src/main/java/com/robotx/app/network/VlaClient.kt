package com.robotx.app.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import com.robotx.app.network.dto.VlaMessage
import com.robotx.app.network.dto.VlaRequest
import com.robotx.app.network.dto.VlaResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Vision-Language-Action client. Sends a multimodal chat request to the
 * configured cloud endpoint (OpenAI / DashScope compatible) and returns the
 * model's reply.
 *
 * The request shape is:
 * ```json
 * {
 *   "model": "qwen2.5-vl-7b-instruct",
 *   "messages": [
 *     { "role": "system", "content": "..." },
 *     { "role": "user", "content": [
 *         { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,..." } },
 *         { "type": "text", "text": "..." }
 *     ]}
 *   ]
 * }
 * ```
 *
 * For videos we extract up to [VIDEO_FRAME_COUNT] evenly spaced JPEG frames,
 * since most cloud VLA endpoints don't accept raw mp4 over HTTP.
 */
object VlaClient {

    private const val TAG = "VlaClient"
    private const val MAX_IMAGE_DIM = 1024
    private const val VIDEO_FRAME_COUNT = 4

    /** 纯文本问答：让模型直接用自然中文回答，不要输出 JSON 或代码块。 */
    private const val SYSTEM_PROMPT_TEXT =
        "你是 Fly Agent，一个友好的中文助手。请直接用自然、简洁的中文回答用户的问题，" +
        "不要输出 JSON、代码块、Markdown 或任何格式化标记，也不要使用占位符。"

    /** 视觉问答：先描述画面，再回答问题，必要时再追加动作 JSON 块。 */
    private const val SYSTEM_PROMPT_VISION =
        "你是 Fly Agent，一个机器人助手。请先用一两句话描述图像/视频中的内容，" +
        "再用中文回答用户的问题。若用户明确要求动作建议，可以在最末尾追加一个 " +
        "```json {\"action\":\"动作名\",\"params\":{...}}``` 代码块；" +
        "如果不需要动作建议则不要输出任何 JSON。"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun infer(
        prompt: String,
        imageFile: File? = null,
        videoFile: File? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        val cfg = ApiConfig.snapshot()
        if (!cfg.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Cloud API not configured"))
        }

        val hasImage = imageFile != null && imageFile.exists()
        val hasVideo = videoFile != null && videoFile.exists()
        val hasMedia = hasImage || hasVideo

        val parts = mutableListOf<JsonObject>()
        if (hasImage) {
            val dataUrl = imageFile!!.toCompressedDataUrl()
            parts += buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", dataUrl) })
            }
        }
        if (hasVideo) {
            extractKeyframes(videoFile!!, VIDEO_FRAME_COUNT).forEach { dataUrl ->
                parts += buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", dataUrl) })
                }
            }
        }
        val defaultPrompt = if (hasMedia) "请描述当前画面。" else "你好。"
        parts += buildJsonObject {
            put("type", "text")
            put("text", prompt.ifBlank { defaultPrompt })
        }

        val systemPrompt = if (hasMedia) SYSTEM_PROMPT_VISION else SYSTEM_PROMPT_TEXT

        // 纯文本场景下用 string content 兼容性更好（部分模型不支持 array content 里没有图片）。
        val userContent: JsonElement = if (hasMedia) {
            JsonArray(parts)
        } else {
            JsonPrimitive(prompt.ifBlank { defaultPrompt })
        }

        val req = VlaRequest(
            model = cfg.vlaModel,
            messages = listOf(
                VlaMessage(role = "system", content = JsonPrimitive(systemPrompt)),
                VlaMessage(role = "user", content = userContent),
            ),
            stream = false,
        )

        val body = HttpClient.json.encodeToString(VlaRequest.serializer(), req)
        val request = Request.Builder()
            .url(cfg.resolvedVlaUrl())
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        runCatching { execute(request) }
            .mapCatching { raw ->
                val resp = HttpClient.json.decodeFromString(VlaResponse.serializer(), raw)
                resp.error?.message?.let { throw IllegalStateException(it) }
                val text = resp.pickText()
                if (text.isBlank()) {
                    throw IllegalStateException(resp.message ?: "Empty VLA result: ${raw.take(200)}")
                }
                sanitizeReply(text)
            }
    }

    /**
     * 把模型回复里"成对的代码块"剥掉，只保留正文自然语言。
     *
     * 关键规则：
     * 1. 仅剥掉成对的 ``` 代码块（包括 ```json）。
     * 2. 不再扫描裸 `{ "action": ... }`，因为容易把"含括号的正常文字"误删。
     * 3. 如果剥掉之后剩下的是空白，直接退回原文，绝不再编造"我已完成画面分析"之类的兜底文案，
     *    避免出现"$action"这种占位字符直接呈现给用户。
     */
    private fun sanitizeReply(raw: String): String {
        val fencedJsonRegex = Regex("```json[\\s\\S]*?```", setOf(RegexOption.IGNORE_CASE))
        val genericFenceRegex = Regex("```[\\s\\S]*?```")
        val text = raw
            .replace(fencedJsonRegex, "")
            .replace(genericFenceRegex, "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return text.ifBlank { raw.trim() }
    }

    private suspend fun execute(req: Request): String =
        suspendCancellableCoroutine { cont ->
            val call = HttpClient.client.newCall(req)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) {
                            cont.resumeWithException(IOException("HTTP ${r.code}: ${body.take(400)}"))
                        } else {
                            cont.resume(body)
                        }
                    }
                }
            })
        }

    private fun File.toCompressedDataUrl(): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(absolutePath, opts)
        val sample = computeSampleSize(opts.outWidth, opts.outHeight)
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(absolutePath, decode)
            ?: error("Cannot decode image: $absolutePath")
        return bmp.toJpegDataUrl().also { bmp.recycle() }
    }

    private fun computeSampleSize(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        val longest = maxOf(w, h)
        var sample = 1
        while (longest / sample > MAX_IMAGE_DIM) sample *= 2
        return sample
    }

    private fun Bitmap.toJpegDataUrl(quality: Int = 85): String {
        val baos = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun extractKeyframes(video: File, count: Int): List<String> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.absolutePath)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return emptyList()
            val n = count.coerceAtLeast(1)
            (0 until n).mapNotNull { i ->
                val tUs = (durationMs * 1000L * (i + 1)) / (n + 1)
                runCatching { r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                    .getOrNull()
                    ?.let { bmp ->
                        try {
                            val scaled = bmp.scaleDown(MAX_IMAGE_DIM)
                            val url = scaled.toJpegDataUrl(quality = 80)
                            if (scaled !== bmp) scaled.recycle()
                            bmp.recycle()
                            url
                        } catch (e: Throwable) {
                            Log.w(TAG, "frame encode failed", e)
                            null
                        }
                    }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "extractKeyframes failed", e)
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }

    private fun Bitmap.scaleDown(maxLong: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxLong) return this
        val ratio = maxLong.toFloat() / longest
        val w = (width * ratio).toInt().coerceAtLeast(1)
        val h = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, w, h, true)
    }

    /** Append a json-content block helper for callers that want to extend the request. */
    @Suppress("unused")
    fun extendUserPart(block: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit): JsonElement =
        buildJsonArray(block)
}
