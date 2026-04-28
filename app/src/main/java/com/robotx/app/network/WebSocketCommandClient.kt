package com.robotx.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Simple websocket command channel:
 * App sends a plain text command, server replies with a plain text ack.
 *
 * Default endpoint derived from Base URL:
 *   http://host:8000/v1 -> ws://host:8000/v1/ws/commands
 *   https://host/v1     -> wss://host/v1/ws/commands
 */
object WebSocketCommandClient {

    suspend fun sendCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        val cfg = ApiConfig.snapshot()
        if (!cfg.isConfigured) {
            return@withContext Result.failure(IllegalStateException("尚未配置 Base URL 或 API Key"))
        }
        val wsUrl = deriveWsUrl(cfg.baseUrl)
        runCatching { executeOnce(wsUrl, cfg.apiKey, command) }
    }

    private fun deriveWsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://", ignoreCase = true) ->
                "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://", ignoreCase = true) ->
                "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("wss://", ignoreCase = true) ||
                trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            else -> "ws://$trimmed"
        }
        return "$wsBase/ws/commands"
    }

    private fun executeOnce(url: String, apiKey: String, command: String): String {
        val future = CompletableFuture<String>()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()

        val ws = HttpClient.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(command)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!future.isDone) future.complete(text)
                webSocket.close(1000, "done")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = buildString {
                    append("WebSocket 失败: ")
                    append(t.message ?: t::class.java.simpleName)
                    response?.let { append(" (HTTP ${it.code})") }
                    append(" URL=$url")
                }
                if (!future.isDone) future.completeExceptionally(IOException(detail, t))
            }
        })

        return try {
            future.get(20, TimeUnit.SECONDS)
        } finally {
            runCatching { ws.cancel() }
        }
    }
}
