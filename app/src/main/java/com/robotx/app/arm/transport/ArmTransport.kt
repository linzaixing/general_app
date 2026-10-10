package com.robotx.app.arm.transport

import com.robotx.app.arm.model.ArmFrame
import com.robotx.app.arm.model.ArmLinkState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * 机械臂指令通道的抽象。
 *
 * 现阶段只有 [WsArmTransport]（局域网 `ws://<PC-IP>:8010/ws`）；
 * 以后接蓝牙离线控制器时，新增 `BleArmTransport` 把同样的 JSON 帧写进 GATT 特征即可，
 * Repository 与 UI 一行不用改。
 */
interface ArmTransport {

    /** 下行帧流。热流，多播给 Repository。 */
    val events: SharedFlow<ArmFrame>

    /** 连接状态，驱动 UI 的连线指示。 */
    val link: StateFlow<ArmLinkState>

    /** 发起一次连接。非阻塞，结果经 [link] 与 [events] 反映。 */
    fun connect(endpoint: ArmEndpoint)

    /** 发送一帧指令；返回是否真正写入了通道。 */
    fun send(frame: JsonObject): Boolean

    /** 主动断开并释放资源。 */
    fun close()
}

/** 传输端点。`scheme` 固定 http/ws —— 服务端没有 TLS。 */
data class ArmEndpoint(
    val host: String,
    val port: Int,
    val path: String = "/ws",
) {
    fun wsUrl(): String = "ws://${authority()}$path"

    fun httpBase(): String = "http://${authority()}"

    fun apiUrl(apiPath: String): String =
        httpBase() + if (apiPath.startsWith("/")) apiPath else "/$apiPath"

    /** host 里若自带 `:port`，以 host 为准（用户常直接粘贴带端口的地址）。 */
    private fun authority(): String = if (host.contains(':')) host else "$host:$port"
}