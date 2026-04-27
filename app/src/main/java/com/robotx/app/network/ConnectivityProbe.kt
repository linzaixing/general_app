package com.robotx.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Connectivity probe for cloud LLM endpoints.
 *
 * 旧逻辑对 baseUrl 根路径发 GET，会导致阿里云等服务返回 404，但并不代表
 * chat 接口不可用，容易误导。新逻辑直接对 VLA endpoint 发一个最小 POST，
 * 根据接口返回码判断是否可用。
 */
object ConnectivityProbe {

    data class ProbeResult(
        val ok: Boolean,
        val code: Int,
        val message: String,
    )

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun probeVla(url: String, model: String, apiKey: String): ProbeResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext ProbeResult(false, 0, "VLA 地址为空")
        if (model.isBlank()) return@withContext ProbeResult(false, 0, "VLA 模型名为空")

        val payload = """
            {
              "model": "${model.replace("\"", "\\\"")}",
              "messages": [{"role": "user", "content": "ping"}],
              "max_tokens": 1
            }
        """.trimIndent()

        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON))
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        val req = builder.build()

        runCatching { execute(req) }.getOrElse { e ->
            ProbeResult(false, 0, e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun execute(req: Request): ProbeResult =
        suspendCancellableCoroutine { cont ->
            val call = HttpClient.client.newCall(req)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val code = r.code
                        val excerpt = r.body?.string()?.take(120).orEmpty()
                        val ok = when (code) {
                            200, 201, 400, 401, 403, 405, 429 -> true
                            404 -> false
                            else -> code in 200..299
                        }
                        val msg = when (code) {
                            200, 201 -> "连接成功 (HTTP $code)"
                            400 -> "接口可达，但请求参数校验失败 (HTTP 400)"
                            401, 403 -> "接口可达，但鉴权失败 (HTTP $code)"
                            404 -> "接口路径不存在 (HTTP 404)，请检查 Base URL / VLA 路径"
                            405 -> "接口可达，但方法不被允许 (HTTP 405)"
                            429 -> "接口可达，但已触发限流 (HTTP 429)"
                            else -> "HTTP $code ${r.message}"
                        }
                        cont.resume(
                            ProbeResult(
                                ok = ok,
                                code = code,
                                message = if (excerpt.isBlank()) msg else "$msg: $excerpt",
                            )
                        )
                    }
                }
            })
        }
}
