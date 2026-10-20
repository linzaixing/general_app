package com.smartmood.app.ui

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.smartmood.app.domain.Creature
import io.github.sceneview.NodeScope
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.Node as SvNode

import io.github.sceneview.rememberOnGestureListener
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val MAX_PLACED = 5

private class Placed(val id: Int, val anchor: Anchor, val creature: Creature, val yawDegrees: Float) {
    var bounce by mutableIntStateOf(0)
}

private class FrameHolder {
    var frame: Frame? = null
}

@Composable
fun ArScreen(creature: Creature) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    var recheck by remember { mutableIntStateOf(0) }
    val availability = remember(recheck) { ArCoreApk.getInstance().checkAvailability(context) }

    when {
        !cameraGranted -> Notice(
            title = "需要相机权限",
            body = "AR 需要用相机识别桌面和地面。画面只在手机本地显示，不会上传。",
            action = "允许使用相机",
        ) { permissionLauncher.launch(Manifest.permission.CAMERA) }

        availability.isUnsupported -> Notice(
            title = "这台设备暂不支持 AR",
            body = "打卡、日历和图鉴仍然可以正常使用。支持 ARCore 的设备才能把小精灵放进现实空间。",
            action = null,
        ) {}

        availability.isTransient || availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> Notice(
            title = "需要安装或更新 Google Play 服务 AR",
            body = "这是 Google 提供的 AR 运行组件。安装完成后回到这里，点“重新检测”。",
            action = "安装 / 更新",
            secondary = "重新检测",
            onSecondary = { recheck++ },
        ) {
            (context as? Activity)?.let {
                runCatching { ArCoreApk.getInstance().requestInstall(it, true) }
                    .onFailure { Toast.makeText(context, "无法打开安装页面，请到应用商店搜索“Google Play 服务 AR”", Toast.LENGTH_LONG).show() }
            }
        }

        else -> ArContent(creature)
    }
}

@Composable
private fun Notice(
    title: String,
    body: String,
    action: String?,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    onAction: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, Modifier.padding(vertical = 14.dp), textAlign = TextAlign.Center, color = Color(0xFF7A6A5A))
        if (action != null) Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) { Text(action) }
        if (secondary != null) OutlinedButton(onClick = onSecondary, shape = RoundedCornerShape(14.dp)) { Text(secondary) }
    }
}

@Composable
private fun ArContent(creature: Creature) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val placed = remember { mutableStateListOf<Placed>() }
    val frameHolder = remember { FrameHolder() }
    var nextId by remember { mutableIntStateOf(1) }
    var planeFound by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            planeRenderer = true,
            onSessionUpdated = { _, frame ->
                frameHolder.frame = frame
                val isTracking = frame.camera.trackingState == TrackingState.TRACKING
                if (isTracking != tracking) tracking = isTracking
                if (!planeFound && frame.getUpdatedTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }) planeFound = true
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, node ->
                    var target: SvNode? = node
                    while (target != null && target.name?.startsWith("creature-") != true) target = target.parent
                    val tappedId = target?.name?.removePrefix("creature-")?.toIntOrNull()
                    val existing = placed.firstOrNull { it.id == tappedId }
                    if (existing != null) {
                        existing.bounce++
                    } else {
                        val frame = frameHolder.frame
                        val hit = frame?.hitTest(event.x, event.y)?.firstOrNull {
                            val trackable = it.trackable
                            trackable is Plane && trackable.isPoseInPolygon(it.hitPose) &&
                                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING
                        }
                        if (hit != null && frame.camera.trackingState == TrackingState.TRACKING) {
                            if (placed.size >= MAX_PLACED) {
                                placed.removeAt(0).anchor.detach()
                            }
                            val anchor = hit.createAnchor()
                            val local = anchor.pose.inverse().transformPoint(frame.camera.pose.translation)
                            val yaw = Math.toDegrees(Math.atan2(local[0].toDouble(), local[2].toDouble())).toFloat()
                            placed.add(Placed(nextId++, anchor, creature, yaw))
                        }
                    }
                },
            ),
        ) {
            val materials = rememberCreatureMaterials(creature, materialLoader)
            placed.forEach { item ->
                AnchorNode(anchor = item.anchor, apply = { name = "creature-${item.id}" }) {
                    BouncingCreature(item.creature, materials, item.yawDegrees, item.bounce)
                }
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 16.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    !tracking -> "正在识别环境，请缓慢移动手机…"
                    !planeFound -> "对准桌面或地面，慢慢左右移动"
                    placed.isEmpty() -> "点击发光的平面，放下「${creature.name}」"
                    else -> "点一点它，会有反应"
                },
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        ) {
            Button(onClick = { capturePhoto(context, rootView) }, shape = RoundedCornerShape(16.dp)) { Text("拍照保存") }
            OutlinedButton(
                onClick = {
                    placed.forEach { it.anchor.detach() }
                    placed.clear()
                },
                enabled = placed.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.85f)),
            ) { Text("清空") }
        }
    }

    LaunchedEffect(Unit) { /* 保持会话与界面同生命周期 */ }
}

@Composable
private fun NodeScope.BouncingCreature(creature: Creature, materials: CreatureMaterials, yaw: Float, bounce: Int) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(bounce) {
        if (bounce > 0) {
            scale.animateTo(1.28f, tween(110))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Node(rotation = Rotation(y = yaw), scale = Scale(scale.value)) {
        CreatureModel(creature, materials)
    }
}

private fun findRenderSurface(view: View): View? {
    if (view is SurfaceView || view is TextureView) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) findRenderSurface(view.getChildAt(i))?.let { return it }
    }
    return null
}

private fun capturePhoto(context: Context, root: View) {
    val surface = findRenderSurface(root.rootView)
    if (surface == null || surface.width == 0 || surface.height == 0) {
        Toast.makeText(context, "暂时无法拍照，请稍后再试", Toast.LENGTH_SHORT).show()
        return
    }
    val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
    val onReady = { ok: Boolean ->
        if (ok) saveBitmap(context, bitmap) else Toast.makeText(context, "拍照失败，请重试", Toast.LENGTH_SHORT).show()
    }
    if (surface is SurfaceView) {
        PixelCopy.request(surface, bitmap, { result -> onReady(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
    } else if (surface is TextureView) {
        surface.getBitmap(bitmap)
        onReady(true)
    }
}

private fun saveBitmap(context: Context, bitmap: Bitmap) {
    val name = "SmartMood_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartMood")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
        return
    }
    val written = runCatching {
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true
    }.getOrDefault(false)
    if (written) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Toast.makeText(context, "已保存到相册 Pictures/SmartMood", Toast.LENGTH_SHORT).show()
    } else {
        resolver.delete(uri, null, null)
        Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
    }
}
