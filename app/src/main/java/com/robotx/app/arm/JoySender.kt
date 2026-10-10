package com.robotx.app.arm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 下发周期 20Hz，与服务端网页版的摇杆频率一致。 */
private const val JOG_INTERVAL_MS = 50L

/** 松手时补发帧的 dt，取服务端允许区间内的一个保守小值。 */
private const val STOP_DT = 0.05

/**
 * 按住方向轮盘 / 高度滑条期间的 20Hz `cart_jog` 发送器。
 *
 * - [active] 变 true 时启动；变 false（松手/离开页面）时协程被取消，
 *   `finally` 里补发一帧「全 0」让服务端立刻停住，不必等它的看门狗。
 * - [enabled] 为 false（未使能 / 未开方向控制）时不发帧，纯粹空转；
 *   这样服务端门禁不会被 20Hz 的无意义帧刷屏。
 * - [axis] / [lift] / [factor] / [onFrame] 用 rememberUpdatedState 取最新值：
 *   拖动过程中轴值每帧都在变，若进 key 会导致协程反复重启、dt 失真。
 *
 * @param axis 水平面方向 (lx, ly)。
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