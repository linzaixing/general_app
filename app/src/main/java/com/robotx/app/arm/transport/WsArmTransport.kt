package com.robotx.app.arm.transport

import com.robotx.app.arm.model.ArmFrame
import com.robotx.app.arm.model.ArmLinkState
import com.robotx.app.arm.model.ArmStateDto
import com.robotx.app.arm.model.GraspResultDto
import com.robotx.app.arm.model.LogLineDto
import com.robotx.app.arm.model.TempAlarmDto
import com.robotx.app.arm.model.TrajFileDto
import com.robotx.app.arm.model.UnknownFrame
import com.robotx.app.network.HttpClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 局域网 WebSocket 传输：`ws://<host>:<port>/ws`。
 *
 * 不复用 [com.robotx.app.network.WebSocketCommandClient]：那是云端网关的
 * `/ws/commands`「一问一答」通道，语义完全不同，两者并存互不影响。
 */
class WsArmTransport(
    private val client: OkHttpClient = defaultClient(),
) : ArmTransport {

    private val _events = MutableSharedFlow<ArmFrame>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<ArmFrame> = _events.asSharedFlow()

    private val _link = MutableStateFlow(ArmLinkState.IDLE)
    override val link: StateFlow<ArmLinkState> = _link.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    override fun connect(endpoint: ArmEndpoint) {
        close()
        _link.value = ArmLinkState.CONNECTING
        val request = Request.Builder().url(endpoint.wsUrl()).build()
        socket = client.newWebSocket(request, Listener())
    }

    override fun send(frame: JsonObject): Boolean {
        val ws = socket ?: return false
        if (_link.value != ArmLinkState.ONLINE) return false
        return ws.send(HttpClient.json.encodeToString(JsonObject.serializer(), frame))
    }

    override fun close() {
        socket?.let {
            runCatching { it.close(1000, "bye") }
            runCatching { it.cancel() }
        }
        socket = null
        _link.value = ArmLinkState.CLOSED
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            _link.value = ArmLinkState.ONLINE
            _events.tryEmit(ArmFrame.Log(LogLineDto(level = "info", msg = "已连接 ${webSocket.request().url}")))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            parse(text)?.let { _events.tryEmit(it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = buildString {
                append("连接中断：")
                append(t.message ?: t::class.java.simpleName)
                response?.let { append(" (HTTP ${it.code})") }
            }
            _link.value = ArmLinkState.RETRYING
            _events.tryEmit(ArmFrame.Log(LogLineDto(level = "error", msg = detail)))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            _link.value = ArmLinkState.CLOSED
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _link.value = ArmLinkState.CLOSED
        }
    }

    /**
     * 先看顶层 `type` 再 decode，避免 kotlinx-serialization 多态判别器
     * 在「同一 type 对应多个形状」时抛异常；未知类型原样保留，方便排查。
     */
    private fun parse(text: String): ArmFrame? {
        val obj = runCatching {
            HttpClient.json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null
        val type = runCatching {
            val el = obj["type"]
            if (el is JsonPrimitive && el !is JsonNull) el.content else null
        }.getOrNull() ?: "state"
        return runCatching {
            when (type) {
                "state" -> ArmFrame.State(HttpClient.json.decodeFromJsonElement(ArmStateDto.serializer(), obj))
                "log" -> ArmFrame.Log(HttpClient.json.decodeFromJsonElement(LogLineDto.serializer(), obj))
                "temp_alarm" -> ArmFrame.TempAlarm(
                    HttpClient.json.decodeFromJsonElement(TempAlarmDto.serializer(), obj)
                )
                "grasp_result" -> ArmFrame.GraspResult(
                    HttpClient.json.decodeFromJsonElement(GraspResultDto.serializer(), obj)
                )
                "traj_list" -> ArmFrame.TrajList(
                    HttpClient.json.decodeFromJsonElement(TrajListEnvelope.serializer(), obj).files
                )
                else -> ArmFrame.Unknown(UnknownFrame(obj))
            }
        }.getOrElse { ArmFrame.Unknown(UnknownFrame(obj)) }
    }

    @kotlinx.serialization.Serializable
    private data class TrajListEnvelope(val files: List<TrajFileDto> = emptyList())

    companion object {
        /**
         * 专用客户端：WS 是长连接，`readTimeout(0)` 才不会被空闲读超时掐断；
         * `pingInterval` 让 OkHttp 自己发 PING 保活（服务端 uvicorn 会自动回 PONG）。
         * **不能**复用 [HttpClient.client]（它 readTimeout=180s）。
         */
        fun defaultClient(): OkHttpClient = HttpClient.client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}