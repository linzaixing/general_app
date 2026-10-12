package com.robotx.app.ui.arm

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robotx.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** 升降刻度轮盘的分段数：9 条刻度线（含两端与中位），输入 [-1,1] 均分 8 段。 */
private const val TICK_STEPS = 8

/**
 * 升降刻度的齿轮咔哒声：SoundPool 播放 `res/raw/tick.wav`
 * （16kHz 单声道 16bit、约 6ms 的指数衰减白噪声 + 2kHz 短脉冲，约 236 字节）。
 *
 * 全 App 单例：SoundPool 创建有开销，多个滑条实例（理论上）共用一份采样。
 * `play(times)` 把一帧内跨过的刻度数补成连续触发（快速滑动不漏声），
 * 音量压到 0.55 —— 它是操作反馈，不是提示音，不能盖过 TTS 播报。
 */
private object TickSound {
    private var pool: SoundPool? = null
    private var soundId = 0

    @Volatile
    private var ready = false

    fun ensure(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        sp.setOnLoadCompleteListener { _, _, status -> if (status == 0) ready = true }
        soundId = sp.load(context, R.raw.tick, 1)
        pool = sp
    }

    fun play(times: Int) {
        if (!ready) return
        repeat(times.coerceIn(1, 3)) {
            pool?.play(soundId, 0.55f, 0.55f, 1, 0, 1f)
        }
    }
}

/**
 * 方向轮盘：圆形拖动盘，触点在盘内的位置决定**方向与速度**（越靠边越快）。
 *
 * 只有一个控件 —— 之前那套「前后左右」四键十字已删除：它和轮盘功能重复，
 * 却按 3×3 占掉一大块方形空间（横屏手机上比轮盘本身还宽），
 * 结果是把方向盘挤成了「按不动的手感」。轮盘本身按住任意方向都能走，够用。
 *
 * ## 视觉（2026-10-08 科技感改版）
 * 由外到内：最外一道硬边环 → 三层外圈光晕 → 金属径向渐变盘面 → 48 格刻度环（每 6 格一条主刻度）
 * → 四向箭头 → 中心球面摇杆帽。按住时整盘提亮、摇杆帽加光晕，
 * 并把**离触点最近的那支箭头**点亮（额外铺一层柔光，如推右上会亮「上」或「右」），
 * 让操作者不必盯着机械臂就能确认当前推的是哪个方向。
 *
 * 颜色一律取自主题：`primary` 是这套 HUD 里唯一的荧光色，
 * 盘的「亮 / 待机 / 置灰」三档只改透明度、不改色相，所以深浅切换不会出现两种强调色。
 *
 * ## 手感（本轮修）
 * 改用 `awaitEachGesture` + `awaitFirstDown`，**按下即生效**。
 * 旧写法 `detectDragGestures` 要先越过触摸阈值才回调 `onDragStart`，
 * 表现是「点了没反应、得先拖一下」——滑条在 2026-09-30 踩过同一个坑，这里一并改掉。
 *
 * 坐标 → 指令的映射（与服务端 `_cart_tick` 对齐，2026-10-11 定案的最终符号约定）：
 * 屏幕上方 = 推上 = `ly:-1` = 径向**远离基座**；下方 = `ly:+1` = 朝向基座。
 * 屏幕右 = `lx:+1` = 切向绕基座**俯视逆时针**；屏幕左 = `lx:-1` = 俯视顺时针。
 * （极坐标映射：切向是「绕基座回转」，与末端当前方位角无关；与服务端
 * `u_t = (-u_ry, u_rx)`、网页版手柄 `lx: dz(ax(0))` 原始轴三端同向。
 * 2026-09-30 曾从 `-n.x` 翻成 `+n.x` 以对齐仿真/ID1 转向，翻后的方向就是本约定，
 * 与服务端语义一致 —— 若将来要改，必须三端（网页 / 服务端 / 本 App）同步。）
 *
 * 尺寸由调用方决定（`Modifier.fillMaxHeight().aspectRatio(1f)` 之类的自适应写法），
 * 这样横竖屏 / 不同机型都吃满可用空间，不用改代码。
 *
 * @param enabled 未使能或方向控制未开时为 false：不响应触摸，并整体置灰。
 * @param onActiveChange 是否处于「按住」状态：true 起 20Hz 发送，false 触发停一帧。
 * @param onAxisChange 归一化方向 (lx, ly)，范围 [-1, 1]。
 */
@Composable
fun JoyPad(
    enabled: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onAxisChange: (lx: Double, ly: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 归一化到单位圆的触点位置：既用来画摇杆帽，也用来判断最近的方向箭头。
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }

    val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val plate = MaterialTheme.colorScheme.surfaceVariant
    val plateDeep = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            fun push(n: Offset) {
                val (lx, ly) = axesOf(n)
                onAxisChange(lx, ly)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                knob = normalizeToUnit(down.position, size)
                push(knob)
                active = true
                onActiveChange(true)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    knob = normalizeToUnit(change.position, size)
                    push(knob)
                    change.consume()
                }
                // 松手必须归零：cart_jog 是增量式的，留着上一帧方向机械臂会一直走。
                knob = Offset.Zero
                active = false
                onAxisChange(0.0, 0.0)
                onActiveChange(false)
            }
        },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            // 置灰 / 待机 / 按住三档亮度，一眼能看出「现在能不能动」。
            val a = when {
                active -> 1f
                enabled -> 0.78f
                else -> 0.38f
            }

            // ① 外圈光晕：三层同心描边，越往外越淡，做出「通电」感。
            listOf(0.06f, 0.11f, 0.19f).forEachIndexed { i, ga ->
                drawCircle(
                    color = accent.copy(alpha = ga * a),
                    radius = r * (1f - i * 0.05f),
                    center = c,
                    style = Stroke(width = 15f - i * 4f),
                )
            }
            // ①′ 最外一道硬边环：光晕负责「发光」，这道负责「边界」——
            // 没有它，光晕在深色网格底上会和背景糊在一起，轮盘的可用范围看不出来。
            drawCircle(
                color = accent.copy(alpha = 0.35f * a),
                radius = r * 0.985f,
                center = c,
                style = Stroke(1.5f),
            )

            // ② 盘面：径向渐变（中心稍亮、外缘压暗），像一块金属球面。
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(plate, plateDeep),
                    center = c,
                    radius = r,
                ),
                radius = r * 0.94f,
                center = c,
            )

            // ③ 刻度环：48 格，每 6 格一条主刻度（12 条），给盘面定标。
            val ticks = 48
            for (i in 0 until ticks) {
                val ang = i * 2.0 * PI / ticks
                val major = i % 6 == 0
                val outer = r * 0.92f
                val inner = if (major) r * 0.82f else r * 0.87f
                drawLine(
                    color = accent.copy(alpha = (if (major) 0.42f else 0.20f) * a),
                    start = Offset(c.x + (cos(ang) * inner).toFloat(), c.y + (sin(ang) * inner).toFloat()),
                    end = Offset(c.x + (cos(ang) * outer).toFloat(), c.y + (sin(ang) * outer).toFloat()),
                    strokeWidth = if (major) 3f else 1.5f,
                )
            }

            // ④ 主环 + 内环。
            drawCircle(color = accent.copy(alpha = 0.85f * a), radius = r * 0.90f, center = c, style = Stroke(3f))
            drawCircle(color = accent.copy(alpha = 0.22f * a), radius = r * 0.74f, center = c, style = Stroke(1.5f))

            // ⑤ 十字参考线：帮助对齐正前 / 正后。
            drawLine(
                color = accent.copy(alpha = 0.18f * a),
                start = Offset(c.x - r * 0.62f, c.y),
                end = Offset(c.x + r * 0.62f, c.y),
                strokeWidth = 2f,
            )
            drawLine(
                color = accent.copy(alpha = 0.18f * a),
                start = Offset(c.x, c.y - r * 0.62f),
                end = Offset(c.x, c.y + r * 0.62f),
                strokeWidth = 2f,
            )

            // ⑥ 四向箭头：0=上 1=右 2=下 3=左。按住时点亮离触点最近的一支。
            val dirs = listOf(Offset(0f, -1f), Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f))
            val lit = if (active) nearestDir(knob) else -1
            dirs.forEachIndexed { i, d ->
                val on = i == lit
                // 点亮的那一向：先铺一层柔光再画箭头，让「正在推哪边」在余光里也能看见。
                if (on) {
                    drawCircle(
                        color = accent.copy(alpha = 0.28f),
                        radius = r * 0.16f,
                        center = Offset(c.x + d.x * r * 0.58f, c.y + d.y * r * 0.58f),
                    )
                }
                drawArrowHead(
                    base = Offset(c.x + d.x * r * 0.52f, c.y + d.y * r * 0.52f),
                    dir = d,
                    len = r * 0.15f,
                    half = r * 0.085f,
                    color = accent.copy(alpha = (if (on) 0.95f else 0.42f * a)),
                )
            }

            // ⑦ 中心轴座 + 球面摇杆帽。
            drawCircle(color = plateDeep.copy(alpha = 0.85f), radius = r * 0.40f, center = c)
            drawCircle(
                color = accent.copy(alpha = 0.18f * a),
                radius = r * 0.40f,
                center = c,
                style = Stroke(1.5f),
            )
            val kr = r * 0.21f
            val travel = r * 0.55f
            val kc = Offset(c.x + knob.x * travel, c.y + knob.y * travel)
            if (active) {
                drawCircle(color = accent.copy(alpha = 0.22f), radius = kr * 1.9f, center = kc)
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent, accent.copy(alpha = 0.5f)),
                    center = Offset(kc.x - kr * 0.35f, kc.y - kr * 0.35f),
                    radius = kr * 1.7f,
                ),
                radius = kr,
                center = kc,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.30f),
                radius = kr * 0.30f,
                center = Offset(kc.x - kr * 0.30f, kc.y - kr * 0.30f),
            )
        }
    }
}

/**
 * 升降刻度轮盘（竖向刻度滑条）：往上拖 = 升，往下拖 = 降，越远越快。
 *
 * 视觉上带 9 格刻度（满升 / 满降 / 中位三处主刻度），刻度位置与升降输入量
 * 一一对应 —— 拇指停在刻度上，服务端收到的 `ry` 就是该刻度值；松手回中（= 不动）。
 *
 * **刻度反馈（v1.0.15）**：拖动每跨过一个内部刻度（i=1..7，两端不算），该刻度
 * 高亮放大弹回一拍（180ms 包络，见 [pulseTick] / [TickSound]），同时播一声极短的
 * 齿轮咔哒（6ms 的 `res/raw/tick.wav`，SoundPool 播放）。快速滑动时按「上次值与
 * 当前值之间夹着的刻度数」补足触发次数，不漏声；按下瞬间的跳变与松手回中
 * **不算跨越**，不会爆响。
 *
 * **为什么松手会弹回中间**：`cart_jog` 是**增量式**的 —— 只要 `ry` 不为 0，
 * 服务端每帧都会继续往同方向走。滑块要是「停在哪就一直走哪」，机械臂会一路顶到
 * 工作空间边界才停（而且用户未必盯着屏幕）。所以这里做成「按住才动、松手归零」，
 * 语义与方向轮盘一致，配合 20Hz 发送器就是「按住持续升降」。
 *
 * 轴语义：服务端是 `dp.z = -ry * scale`，所以**向上 = `ry` 为负**（见 [JoySender]）。
 * 本控件直接输出这个 `ry` 轴 —— 顶部 = 升 = 负值，底部 = 降 = 正值。
 * **2026-10-11 复核**：与服务端 `_cart_tick`（`ry:+1 → 末端下降`）逐项核对，
 * 「顶 = 升 / 底 = 降」即最终符号约定，无需翻转。
 *
 * ⚠️ 2026-09-30 修：以前这里顶部返回 **+1** 并标注「升」，与 `JoySender` 的
 *    「`ry:+1` = 下降」正好相反 —— 运行时表现是「**往上拖，机械臂反而下降**」。
 *    现在控件输出的就是服务端要的 `ry`，屏幕文字的「升/降」与机械臂动作一致。
 *
 * @param enabled 未使能或方向控制未开时为 false：不响应触摸，并整体置灰。
 * @param onActiveChange 是否处于「按住」状态，与轮盘共用同一个 20Hz 发送器。
 * @param onValueChange 归一化高度轴 `ry` [-1, 1]：**负 = 升、正 = 降**、0 = 停。
 */
@Composable
fun HeightSlider(
    enabled: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember { mutableStateOf(0.0) }   // 服务端高度轴 ry：顶部 = 负 = 升
    var active by remember { mutableStateOf(false) }

    // —— 刻度咔哒反馈：跨刻度 → 该刻度放大弹回 + 播一声 tick ——
    // pulse 是 1→0 的弹回包络；pulseTick 记录最近被跨过的刻度序号；
    // pulseSeq 每跨一次 +1，用来重启 LaunchedEffect 的动画协程。
    val pulse = remember { Animatable(0f) }
    var pulseTick by remember { mutableStateOf(-1) }
    var pulseSeq by remember { mutableStateOf(0) }
    LaunchedEffect(pulseSeq) {
        if (pulseSeq > 0) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(durationMillis = 180))
        }
    }
    val ctx = LocalContext.current
    remember(ctx) { TickSound.ensure(ctx); true }

    val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    // 轨道用 surfaceContainerHighest 而不是 surfaceVariant：后者与面板底几乎同色，
    // 在深色 HUD 上滑条的「槽」会整条看不见。
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val glow = if (active) 1f else 0.7f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("升", fontSize = 9.sp, color = labelColor)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    // 触点的 y 相对轨道中点归一化，直接得到服务端 `ry`：
                    // 顶部 = 升 = -1，底部 = 降 = +1，中位 = 0（不动）。
                    fun toRy(y: Float): Double {
                        val half = size.height / 2.0
                        if (half <= 0.0) return 0.0
                        return ((y - half) / half).coerceIn(-1.0, 1.0)
                    }
                    // [from, to] 之间夹着多少个「内部刻度」（i=1..7）。
                    // 两端（满升 / 满降）不算：到达极限不该响，否则顶到底会连响两声。
                    // 半开区间 (lo, hi]：原地或同刻度内微动不会重复计数。
                    fun crossedTicks(from: Double, to: Double): Pair<Int, Int> {
                        val lo = minOf(from, to)
                        val hi = maxOf(from, to)
                        var count = 0
                        var lastIdx = -1
                        for (i in 1..TICK_STEPS - 1) {
                            val g = -1.0 + 2.0 * i / TICK_STEPS
                            if (g > lo && g <= hi) {
                                count++
                                lastIdx = i
                            }
                        }
                        return count to lastIdx
                    }
                    // 用 awaitEachGesture 而不是 detectDragGestures：滑条要**按下即生效**，
                    // detectDragGestures 得先越过触摸阈值才回调 onDragStart，手感是「点了没反应」。
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        value = toRy(down.position.y)
                        var lastV = value   // 按下瞬间的跳变不算「跨越」：从 0 直达不发声
                        onValueChange(value)
                        active = true
                        onActiveChange(true)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val newV = toRy(change.position.y)
                            // 快速滑动一帧可能跨过多个刻度：逐个计数补足触发次数，不漏声。
                            val (count, idx) = crossedTicks(lastV, newV)
                            if (count > 0) {
                                TickSound.play(count)
                                pulseTick = idx
                                pulseSeq++
                            }
                            lastV = newV
                            value = newV
                            onValueChange(value)
                            change.consume()
                        }
                        // 松手必须归零，否则服务端会一直按最后一帧的 ry 走。
                        // 回中路径刻意**不**触发咔哒（lastV 不参与）：松手静默回中。
                        value = 0.0
                        active = false
                        onValueChange(0.0)
                        onActiveChange(false)
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackW = 14.dp.toPx()
                val cx = size.width / 2f
                val pad = 4.dp.toPx()
                val top = pad
                val bottom = size.height - pad
                if (bottom <= top) return@Canvas
                val mid = (top + bottom) / 2f
                val half = (bottom - top) / 2f

                // 轨道外发光：与轮盘同一套语言。
                drawRoundRect(
                    color = accent.copy(alpha = 0.10f * glow),
                    topLeft = Offset(cx - trackW, top - 3f),
                    size = Size(trackW * 2f, bottom - top + 6f),
                    cornerRadius = CornerRadius(trackW),
                )
                // 刻度线（先画，压在轨道后面）：8 段 9 格，0=满升、1=满降，
                // 满升 / 满降 / 中位三处是长主刻度，其余是短刻度。
                // 刻度位置与升降输入量一一对应：拇指停在刻度上 = 服务端收到的
                // `ry` 就是那个刻度值（中位 0 = 不动，顶格 = 最快升，底格 = 最快降）。
                // 被跨过的刻度（pulseTick）随 pulse 包络放大 + 提亮，180ms 弹回。
                for (i in 0..TICK_STEPS) {
                    val f = i / TICK_STEPS.toFloat()
                    val y = top + (bottom - top) * f
                    val major = i == 0 || i == TICK_STEPS || i == TICK_STEPS / 2
                    val baseLen = if (major) trackW * 1.5f else trackW * 0.85f
                    val pulseAmt = if (i == pulseTick) pulse.value else 0f
                    val len = baseLen * (1f + 0.7f * pulseAmt)
                    val baseAlpha = if (major) 0.55f else 0.25f
                    drawLine(
                        color = accent.copy(alpha = (baseAlpha + 0.45f * pulseAmt).coerceAtMost(1f) * glow),
                        start = Offset(cx - len, y),
                        end = Offset(cx + len, y),
                        strokeWidth = if (major || pulseAmt > 0.3f) 2f else 1.5f,
                    )
                }
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(cx - trackW / 2f, top),
                    size = Size(trackW, bottom - top),
                    cornerRadius = CornerRadius(trackW / 2f),
                )
                // 偏离中位的一段高亮，一眼看出「正在升 / 正在降」。
                // `value` 是服务端 `ry`：升为负 → 拇指往上画，所以是 `mid + value*half`。
                val thumbY = mid + (value * half).toFloat()
                val offset = abs(thumbY - mid)
                if (offset > 1f) {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.35f * glow),
                        topLeft = Offset(cx - trackW / 2f, minOf(thumbY, mid)),
                        size = Size(trackW, offset),
                        cornerRadius = CornerRadius(trackW / 2f),
                    )
                }
                // 中位刻度 = 不动。
                drawLine(
                    color = accent.copy(alpha = 0.55f * glow),
                    start = Offset(cx - trackW, mid),
                    end = Offset(cx + trackW, mid),
                    strokeWidth = 2f,
                )
                val thumbW = 40.dp.toPx()
                val thumbH = 22.dp.toPx()
                if (active) {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.25f),
                        topLeft = Offset(cx - thumbW / 2f - 3f, thumbY - thumbH / 2f - 3f),
                        size = Size(thumbW + 6f, thumbH + 6f),
                        cornerRadius = CornerRadius(thumbH),
                    )
                }
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(cx - thumbW / 2f, thumbY - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(thumbH / 2f),
                )
            }
        }
        Text("降", fontSize = 9.sp, color = labelColor)
    }
}

/**
 * 画一个朝 [dir]（单位向量、指向外侧）的小三角箭头。
 *
 * @param base 三角形底边中点所在的圆周点。
 * @param len  箭头长度（从 [base] 往外量）。
 */
private fun DrawScope.drawArrowHead(
    base: Offset,
    dir: Offset,
    len: Float,
    half: Float,
    color: Color,
) {
    val tip = Offset(base.x + dir.x * len, base.y + dir.y * len)
    // 底边两角：沿垂直于 dir 的方向各张开 half。
    val px = -dir.y
    val py = dir.x
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + px * half, base.y + py * half)
        lineTo(base.x - px * half, base.y - py * half)
        close()
    }
    drawPath(path, color)
}

/** 触点方向最近的四向箭头序号：0=上 1=右 2=下 3=左。 */
private fun nearestDir(n: Offset): Int {
    if (n == Offset.Zero) return -1
    // atan2 得到 -π..π，除以 π/2 后四舍五入即落在四个象限上（右=0，逆时针递增）。
    val quadrant = (atan2(n.y.toDouble(), n.x.toDouble()) / (PI / 2)).roundToInt()
    return when (((quadrant % 4) + 4) % 4) {
        0 -> 1   // +x → 右
        1 -> 2   // +y（屏幕向下）→ 下
        2 -> 3   // -x → 左
        else -> 0 // -y → 上
    }
}

/**
 * 把触点相对圆心归一化到单位圆内，得到屏幕坐标下的 (x 右正, y 下正)。
 *
 * 归一化半径用 **min(w,h)/2（内切圆半径）** 而不是各自的半宽/半高：
 * 背景圆就是按内切圆画的，这样「控制原点 = 圆心、满偏 = 圆缘」与视觉严格一致；
 * 旧写法按半宽/半高各归一化，在非正方形容器里是椭圆归一化，
 * 触点与摇杆帽的视觉位置对不上，也表现为「中心原点不在圆心」。
 */
private fun normalizeToUnit(pos: Offset, size: IntSize): Offset {
    if (size.width <= 0 || size.height <= 0) return Offset.Zero
    val r = minOf(size.width, size.height) / 2f
    if (r <= 0f) return Offset.Zero
    val cx = size.width / 2f
    val cy = size.height / 2f
    val dx = (pos.x - cx) / r
    val dy = (pos.y - cy) / r
    val len = hypot(dx, dy)
    return if (len > 1f) Offset(dx / len, dy / len) else Offset(dx, dy)
}

/**
 * 屏幕坐标 → 指令轴（2026-10-11 最终符号约定，见类文档与服务端 `_cart_tick`）：
 * 屏幕右 = `lx:+1` = 切向俯视逆时针；向下为「后」= `ly:+1` = 朝向基座（推上 = `ly:-1` = 远离基座）。
 */
private fun axesOf(n: Offset): Pair<Double, Double> =
    n.x.toDouble().coerceIn(-1.0, 1.0) to n.y.toDouble().coerceIn(-1.0, 1.0)