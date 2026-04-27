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
    private const val SYSTEM_PROMPT =
        "You are Robot X, a robot assistant. Describe what you see in the image" +
        " or video and, when appropriate, output the next action as a JSON code" +
        " block like ```json {\"action\":\"...\",\"params\":{...}} ``` ."

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

        val parts = mutableListOf<JsonObject>()
        if (imageFile != null && imageFile.exists()) {
            val dataUrl = imageFile.toCompressedDataUrl()
            parts += buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", dataUrl) })
            }
        }
        if (videoFile != null && videoFile.exists()) {
            extractKeyframes(videoFile, VIDEO_FRAME_COUNT).forEach { dataUrl ->
                parts += buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", dataUrl) })
                }
            }
        }
        parts += buildJsonObject {
            put("type", "text")
            put("text", prompt.ifBlank { "请描述当前画面，并给出下一步动作建议。" })
        }

        val req = VlaRequest(
            model = cfg.vlaModel,
            messages = listOf(
                VlaMessage(role = "system", content = JsonPrimitive(SYSTEM_PROMPT)),
                VlaMessage(role = "user", content = JsonArray(parts)),
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
                text
            }
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
