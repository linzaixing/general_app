package com.robotx.app.ui.arm

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.robotx.app.arm.model.JointDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** URDF 里的 revolute 关节数（ID1~ID5），与页面里 jointNodes 的顺序一一对应。 */
private const val URDF_JOINT_COUNT = 5

/** 推送节流：10Hz 足够跟手，又不会让 evaluateJavascript 抢 UI 线程。 */
private const val PUSH_INTERVAL_MS = 100L

/** 关节角变化小于这个值就不推（静止时完全不下发 JS）。 */
private const val PUSH_EPSILON = 1e-4

private const val VIEWER_URL = "https://appassets.androidplatform.net/assets/viewer/index.html"

/**
 * URDF 3D 视窗：assets 里的 three.js 页面渲染真实模型，可拖动旋转 / 双指缩放，
 * 并按服务端给的 URDF 关节角 `q` 实时摆姿态。
 *
 * 为什么走 WebView 而不是原生 OpenGL：URDF + STL + OrbitControls 这套在
 * three.js 上已经有现成实现；换成原生要自己写 STL 解析与关节树，成本高得多。
 * 页面与 App 之间只有一条窄接口 —— `window.setJoints([...])`。
 *
 * @param joints 服务端 state 里的关节数组（已含只读派生量 `q`）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun UrdfView(
    joints: List<JointDto>,
    modifier: Modifier = Modifier,
) {
    val webView = remember { mutableStateOf<WebView?>(null) }
    // 与服务端 joints 顺序一致：ID1~ID5。
    val qs = remember(joints) { joints.sortedBy { it.id }.take(URDF_JOINT_COUNT).map { it.q } }
    val qsState = rememberUpdatedState(qs)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.parseColor("#12161C"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webViewClient = object : WebViewClient() {
                    private val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                        .build()

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                }
                loadUrl(VIEWER_URL)
                webView.value = this
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            webView.value?.apply {
                stopLoading()
                destroy()
            }
            webView.value = null
        }
    }

    // 10Hz 把关节角推给页面（变化小于 eps 就整帧跳过，静止时零开销）。
    LaunchedEffect(Unit) {
        var last: List<Double?> = emptyList()
        while (isActive) {
            delay(PUSH_INTERVAL_MS)
            val wv = webView.value ?: continue
            val cur = qsState.value
            if (cur.isEmpty() || !moved(cur, last)) continue
            last = cur
            wv.evaluateJavascript("window.setJoints(${toJsArray(cur)})", null)
        }
    }
}

private fun moved(cur: List<Double?>, last: List<Double?>): Boolean {
    if (cur.size != last.size) return true
    for (i in cur.indices) {
        val a = cur[i]
        val b = last[i]
        if (a == null || b == null) {
            if (a != b) return true
            continue
        }
        if (kotlin.math.abs(a - b) > PUSH_EPSILON) return true
    }
    return false
}

private fun toJsArray(qs: List<Double?>): String =
    qs.joinToString(prefix = "[", postfix = "]") { q ->
        if (q == null || !q.isFinite()) "null" else q.toString()
    }