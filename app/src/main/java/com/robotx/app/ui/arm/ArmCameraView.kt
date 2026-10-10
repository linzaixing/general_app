package com.robotx.app.ui.arm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.robotx.app.arm.model.CameraDto
import com.robotx.app.arm.transport.ArmEndpoint
import com.robotx.app.arm.transport.HttpArmApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 正常取图节拍：8fps（先做最简版，够看清画面即可；MJPEG 长流留待后续升级）。 */
private const val FRAME_INTERVAL_MS = 125L

/** 取不到图时的退避上限 —— 到这一步就不再是「预览」，只是低频探活。 */
private const val IDLE_INTERVAL_MS = 2_000L

/** 每次失败间隔翻倍，直到 [IDLE_INTERVAL_MS]。 */
private const val FAIL_BACKOFF = 2

/**
 * 相机预览：按 [FRAME_INTERVAL_MS] 轮询 `/api/camera/snapshot`。
 *
 * 用轮询而不是 MJPEG 长流：长流需要另开 `readTimeout(0)` 的 OkHttpClient 并手写
 * multipart 边界解析，属于后续升级项；8fps 单帧已经能满足「看得见」。
 * 点一下画面可在腕部 / 全局相机之间切换（对应网页的两个相机）。
 *
 * ⚠️ **取不到图时必须退避**，不能一直以 8fps 猛打：服务端每次抓图都会去读设备，
 * 若那一路相机是「能打开但读不到帧」的假设备，读操作会阻塞重试（实测单个索引
 * 就要几十秒），与同进程的控制循环争用后**真机运动会出现卡顿与震颤**。
 * 退避到 2s 后这点开销可以忽略，同时相机恢复可用时能在 2s 内自动接上。
 *
 * 无画面时优先显示服务端给的 `msg`（例如「相机 #0 打开失败（被占用或不存在）」），
 * 比一句笼统的「无相机画面」有用得多。
 *
 * @param endpoint 服务端地址；为 null（未配置）时只显示提示，不发请求。
 * @param camera   服务端状态帧里的相机信息（两路的 available / index / msg）。
 */
@Composable
fun ArmCameraView(
    endpoint: ArmEndpoint?,
    camera: CameraDto = CameraDto(),
    modifier: Modifier = Modifier,
) {
    var cam by remember { mutableStateOf(CAM_WRIST) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var live by remember { mutableStateOf(false) }

    LaunchedEffect(endpoint, cam) {
        val ep = endpoint
        if (ep == null) return@LaunchedEffect
        val api = HttpArmApi(ep)
        var interval = FRAME_INTERVAL_MS
        while (isActive) {
            val started = System.currentTimeMillis()
            val bytes = api.snapshotJpeg(cam)
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                frame = bitmap
                live = true
                interval = FRAME_INTERVAL_MS
            } else {
                frame = null
                live = false
                interval = (interval * FAIL_BACKOFF).coerceAtMost(IDLE_INTERVAL_MS)
            }
            val used = System.currentTimeMillis() - started
            delay((interval - used).coerceAtLeast(50L))
        }
    }

    val info = if (cam == CAM_WRIST) camera.wrist else camera.global
    val note = when {
        endpoint == null -> "未配置服务地址"
        live -> "点击切换相机"
        info.msg.isNotBlank() -> info.msg
        !info.available -> "该路相机未打开（请检查首页的相机设备号）"
        else -> "正在取图…"
    }
    val title = (if (cam == CAM_WRIST) "腕部相机" else "全局相机") +
        if (info.available) " #${info.index} ${info.width}×${info.height}" else ""

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    frame = null
                    live = false
                    cam = if (cam == CAM_WRIST) CAM_GLOBAL else CAM_WRIST
                },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = frame
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "相机画面",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

private const val CAM_WRIST = "wrist"
private const val CAM_GLOBAL = "global"