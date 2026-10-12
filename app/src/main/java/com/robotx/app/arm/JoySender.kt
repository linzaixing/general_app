package com.robotx.app.arm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 按住期间 `cart_jog` 的最终发送频率：**固定 30Hz（33ms/帧）**。
 *
 * 排查结论（v1.1.0）：本发送器自 v1.0.x 起就是「按住期间固定间隔连发」（摇杆回中
 * 也照发零值帧），并不依赖触点变化触发 —— 但此前是 20Hz（50ms）。用户反馈 IK 操控
 * 顿挫、像点动；服务端已确认其侧平滑充分（200Hz MIT 位置控制环 + 0.5 rad/s 限幅器
 * 斜坡，每帧增量经限幅），顿挫来自 App 端指令节奏偏稀：增量式指令在 20Hz 下一帧
 * 走完 50ms 的位移，帧间阶梯感明显。提高到 30Hz 后单帧位移更小、指令到达节奏更密，
 * 与服务端控制环的衔接更平滑。消息格式与服务端均未改动；再往上提频收益递减且
 * 徒增 WS 帧量，30Hz 是本轮的最终值。
 */
private const val JOG_INTERVAL_MS = 33L

/** 松手时补发帧的 dt，取服务端允许区间内的一个保守小值。 */
private const val STOP_DT = 0.05

/**
 * 按住方向轮盘 / 高度滑条期间的 30Hz `cart_jog` 发送器。
 *
 * - [active] 变 true 时启动；变 false（松手/离开页面）时协程被取消，
 *   `finally` 里补发一帧「全 0」让服务端立刻停住，不必等它的看门狗。
 * - [enabled] 为 false（未使能 / 未开方向控制）时不发帧，纯粹空转；
 *   这样服务端门禁不会被 30Hz 的无意义帧刷屏。
 * - [axis] / [lift] / [factor] / [onFrame] 用 rememberUpdatedState 取最新值：
 *   拖动过程中轴值每帧都在变，若进 key 会导致协程反复重启、dt 失真。
 *
 * @param axis 水平面方向 (lx, ly)：**推上 = `ly:-1` = 远离基座、推右 = `lx:+1` =
 *   切向俯视逆时针**（2026-10-11 与服务端 `_cart_tick` 核对的最终约定，见
 *   [com.robotx.app.arm.ArmRepository.cartJog]）。
 * @param lift 高度轴 `ry`：**正 = 下降、负 = 上升**（服务端 `dp.z = -ry*scale`）。
 */
@Composable
fun JoyJogEffect(
    active: Boolean,
    enabled: Boolean,
    axis: Pair<Double, Double>,
    lift: Double,
    factor: Double,
    onFrame: (lx: Double, ly: Double, ry: Double, dt: Double, factor: Double) -> Unit,
) {
    val axisState = rememberUpdatedState(axis)
    val liftState = rememberUpdatedState(lift)
    val factorState = rememberUpdatedState(factor)
    val frameState = rememberUpdatedState(onFrame)
    val enabledState = rememberUpdatedState(enabled)

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var moving = false
        try {
            var lastNs = System.nanoTime()
            while (isActive) {
                delay(JOG_INTERVAL_MS)
                val nowNs = System.nanoTime()
                val dt = ((nowNs - lastNs) / 1_000_000_000.0).coerceIn(MIN_JOG_DT, MAX_JOG_DT)
                lastNs = nowNs
                if (!enabledState.value) {
                    moving = false
                    continue
                }
                val (lx, ly) = axisState.value
                frameState.value(lx, ly, liftState.value, dt, factorState.value)
                moving = true
            }
        } finally {
            if (moving) frameState.value(0.0, 0.0, 0.0, STOP_DT, factorState.value)
        }
    }
}
