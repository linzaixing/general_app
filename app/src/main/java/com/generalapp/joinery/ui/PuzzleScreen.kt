package com.generalapp.joinery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generalapp.joinery.GameViewModel
import com.generalapp.joinery.domain.Level
import com.generalapp.joinery.render.Camera
import com.generalapp.joinery.render.buildFaces
import com.generalapp.joinery.render.drawVoxels
import com.generalapp.joinery.render.axisOfDelta
import com.generalapp.joinery.render.hitTest
import com.generalapp.joinery.render.projectOntoFace
import com.generalapp.joinery.render.snapCut
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Tool { CARVE, ROTATE }

@Composable
fun PuzzleScreen(vm: GameViewModel, level: Level, onExit: () -> Unit) {
    var camera by remember(level.id) { mutableStateOf(Camera()) }
    var tool by remember(level.id) { mutableStateOf(Tool.CARVE) }
    var showHint by remember(level.id) { mutableStateOf(false) }

    fun modelScale(w: Float, h: Float, n: Int, zoom: Float): Float =
        min(h * 0.60f / n, w * 0.44f / (n * 1.5f)) * zoom

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onExit) { Text("‹ 关卡", color = Brass) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "第 ${level.id} 关 · ${level.title}",
                    color = Paper,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(level.chapterTitle, color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("步数 ${vm.actions}", color = Paper, fontSize = 14.sp)
                Text("目标 ${level.par}", color = Muted, fontSize = 11.sp)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(level.id, vm.voxels, tool) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            var last = start
                            var moved = 0f
                            var twoFinger = false
                            var prevCentroid = start
                            var prevDistance = 0f

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    twoFinger = true
                                    val centroid = Offset(
                                        pressed.map { it.position.x }.average().toFloat(),
                                        pressed.map { it.position.y }.average().toFloat(),
                                    )
                                    val distance = if (pressed.size >= 2) {
                                        (pressed[0].position - pressed[1].position).getDistance()
                                    } else 0f
                                    if (prevDistance > 0f && distance > 0f) {
                                        camera = camera.copy(
                                            zoom = (camera.zoom * (distance / prevDistance)).coerceIn(0.65f, 2.0f),
                                        )
                                    }
                                    camera = camera.copy(
                                        yaw = camera.yaw + (centroid.x - prevCentroid.x) * 0.35f,
                                        pitch = (camera.pitch - (centroid.y - prevCentroid.y) * 0.35f).coerceIn(-78f, 78f),
                                    )
                                    prevCentroid = centroid
                                    prevDistance = distance
                                } else if (!twoFinger) {
                                    val p = pressed.first().position
                                    moved += (p - last).getDistance()
                                    if (tool == Tool.ROTATE) {
                                        camera = camera.copy(
                                            yaw = camera.yaw + (p.x - last.x) * 0.42f,
                                            pitch = (camera.pitch - (p.y - last.y) * 0.42f).coerceIn(-78f, 78f),
                                        )
                                    }
                                    last = p
                                }
                                pressed.forEach { it.consume() }
                            }

                            if (twoFinger) return@awaitEachGesture
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val n = level.size
                            val scale = modelScale(w, h, n, camera.zoom)
                            val cx = w * 0.27f
                            val cy = h * 0.53f
                            val faces = buildFaces(vm.voxels, n, camera, cx, cy, scale)

                            if (moved < 18f) {
                                hitTest(faces, start, n, cx, cy, scale)?.let { vm.chisel(it.face.voxel) }
                            } else if (tool == Tool.CARVE) {
                                val hit = hitTest(faces, start, n, cx, cy, scale)
                                if (hit != null) {
                                    val endWorld = projectOntoFace(hit.face, last, n, cx, cy, scale)
                                    if (endWorld != null) {
                                        val (axis, delta, endCoord) = axisOfDelta(hit.world, endWorld)
                                        if (abs(delta) >= 0.6f) {
                                            val k = snapCut(endCoord + n / 2f, n)
                                            if (k != null) vm.cut(axis, k, delta > 0)
                                        }
                                    }
                                }
                            }
                        }
                    },
            ) {
                drawRect(
                    Brush.verticalGradient(listOf(Color(0xFF2A1E15), Color(0xFF120D09))),
                )
                val n = level.size
                val scale = modelScale(size.width, size.height, n, camera.zoom)
                val cy = size.height * 0.53f
                drawOval(
                    color = Color(0x33000000),
                    topLeft = Offset(size.width * 0.27f - scale * n * 0.62f, cy + scale * n * 0.52f),
                    size = androidx.compose.ui.geometry.Size(scale * n * 1.24f, scale * n * 0.26f),
                )
                drawOval(
                    color = Color(0x33000000),
                    topLeft = Offset(size.width * 0.73f - scale * n * 0.62f, cy + scale * n * 0.52f),
                    size = androidx.compose.ui.geometry.Size(scale * n * 1.24f, scale * n * 0.26f),
                )
                drawVoxels(
                    buildFaces(vm.voxels, n, camera, size.width * 0.27f, cy, scale),
                    base = Wood,
                )
                drawVoxels(
                    buildFaces(level.mortise, n, camera, size.width * 0.73f, cy, scale),
                    base = Color(0xFF8E6440),
                    edge = Color(0x33000000),
                )
            }

            Text(
                "你的木料",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 6.dp),
            )
            Text(
                "卯眼",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 40.dp, top = 6.dp),
            )
            Text(
                when {
                    tool == Tool.ROTATE -> "单指旋转 · 双指缩放"
                    vm.actions == 0 -> "拖动切割木料，轻点凿掉一块"
                    else -> "继续修整，直到与卯眼完全吻合"
                },
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }

        if (showHint) {
            Text(
                level.hint,
                color = Paper,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp)
                    .background(PanelHigh, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { vm.undo() },
                enabled = vm.canUndo,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) { Text("撤销", fontSize = 13.sp) }
            OutlinedButton(
                onClick = { vm.reset() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) { Text("重置", fontSize = 13.sp) }
            OutlinedButton(
                onClick = { tool = if (tool == Tool.CARVE) Tool.ROTATE else Tool.CARVE },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (tool == Tool.ROTATE) PanelHigh else Color.Transparent,
                ),
            ) { Text(if (tool == Tool.CARVE) "雕刻" else "旋转", fontSize = 13.sp) }
            Button(
                onClick = { showHint = !showHint },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PanelHigh, contentColor = Brass),
            ) { Text("提示", fontSize = 13.sp) }
        }
    }

    vm.result?.let { result ->
        AlertDialog(
            onDismissRequest = { },
            containerColor = Panel,
            title = {
                Text(
                    if (result.stars == 3) "严丝合缝" else "拼合成功",
                    color = Paper,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column {
                    Text(
                        "★".repeat(result.stars) + "☆".repeat(3 - result.stars),
                        color = Brass,
                        fontSize = 26.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${result.actions} 步完成，目标 ${result.level.par} 步" +
                            if (result.isNewBest) " · 新纪录" else "",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "「${level.joint.title}」已收入图鉴。",
                        color = Paper,
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                val next = vm.nextLevel()
                Button(
                    onClick = {
                        vm.dismissResult()
                        if (next != null) vm.open(next) else onExit()
                    },
                    shape = RoundedCornerShape(10.dp),
                ) { Text(if (next != null) "下一关" else "返回关卡") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissResult(); onExit() }) {
                    Text("关卡列表", color = Brass)
                }
            },
        )
    }
}
