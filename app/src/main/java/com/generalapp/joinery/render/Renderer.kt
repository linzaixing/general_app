package com.generalapp.joinery.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.generalapp.joinery.domain.V3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class P3(val x: Float, val y: Float, val z: Float)

data class Camera(val yaw: Float = -32f, val pitch: Float = 24f, val zoom: Float = 1f)

private data class Dir(val normal: V3, val corners: (Int, Int, Int) -> List<V3>)

private val dirs = listOf(
    Dir(V3(-1, 0, 0)) { x, y, z -> listOf(V3(x, y, z), V3(x, y + 1, z), V3(x, y + 1, z + 1), V3(x, y, z + 1)) },
    Dir(V3(1, 0, 0)) { x, y, z -> listOf(V3(x + 1, y, z), V3(x + 1, y, z + 1), V3(x + 1, y + 1, z + 1), V3(x + 1, y + 1, z)) },
    Dir(V3(0, -1, 0)) { x, y, z -> listOf(V3(x, y, z), V3(x, y, z + 1), V3(x + 1, y, z + 1), V3(x + 1, y, z)) },
    Dir(V3(0, 1, 0)) { x, y, z -> listOf(V3(x, y + 1, z), V3(x + 1, y + 1, z), V3(x + 1, y + 1, z + 1), V3(x, y + 1, z + 1)) },
    Dir(V3(0, 0, -1)) { x, y, z -> listOf(V3(x, y, z), V3(x + 1, y, z), V3(x + 1, y + 1, z), V3(x, y + 1, z)) },
    Dir(V3(0, 0, 1)) { x, y, z -> listOf(V3(x, y, z + 1), V3(x, y + 1, z + 1), V3(x + 1, y + 1, z + 1), V3(x + 1, y, z + 1)) },
)

class Face(
    val voxel: V3,
    val normal: V3,
    val screen: List<Offset>,
    val world: List<P3>,
    val cameraSpace: List<P3>,
    val depth: Float,
    val shade: Float,
)

data class FaceHit(val face: Face, val world: P3)

private fun rotate(p: P3, cam: Camera): P3 {
    val yaw = Math.toRadians(cam.yaw.toDouble())
    val pitch = Math.toRadians(cam.pitch.toDouble())
    val cy = cos(yaw); val sy = sin(yaw)
    val cp = cos(pitch); val sp = sin(pitch)
    val x1 = p.x * cy + p.z * sy
    val z1 = -p.x * sy + p.z * cy
    val y2 = p.y * cp - z1 * sp
    val z2 = p.y * sp + z1 * cp
    return P3(x1.toFloat(), y2.toFloat(), z2.toFloat())
}

private fun perspective(r: P3, distance: Float): Float = distance / (distance - r.z)

fun buildFaces(
    voxels: Set<V3>,
    size: Int,
    cam: Camera,
    cx: Float,
    cy: Float,
    scale: Float,
): List<Face> {
    val half = size / 2f
    val distance = size * 3.4f
    val lx = 0.35f; val ly = 0.86f; val lz = 0.38f
    val len = sqrt(lx * lx + ly * ly + lz * lz)
    val result = ArrayList<Face>(voxels.size * 3)
    for (voxel in voxels) {
        for (dir in dirs) {
            if ((voxel + dir.normal) in voxels) continue
            val n = rotate(P3(dir.normal.x.toFloat(), dir.normal.y.toFloat(), dir.normal.z.toFloat()), cam)
            if (n.z <= 0.001f) continue
            val world = dir.corners(voxel.x, voxel.y, voxel.z).map {
                P3(it.x - half, it.y - half, it.z - half)
            }
            val camPts = world.map { rotate(it, cam) }
            val screen = camPts.map {
                val f = perspective(it, distance)
                Offset(cx + it.x * f * scale, cy - it.y * f * scale)
            }
            val depth = camPts.map { it.z }.average().toFloat()
            val lambert = max(0f, (dir.normal.x * lx + dir.normal.y * ly + dir.normal.z * lz) / len)
            val shade = (0.52f + 0.48f * lambert) * ambientOcclusion(voxels, voxel, dir)
            result.add(Face(voxel, dir.normal, screen, world, camPts, depth, shade))
        }
    }
    return result.sortedBy { it.depth }
}

private fun rayFor(point: Offset, cx: Float, cy: Float, scale: Float, distance: Float): P3 =
    P3((point.x - cx) / scale, -(point.y - cy) / scale, -distance)

/** 返回屏幕点与三角形(相机坐标)的交点重心坐标，未命中返回 null。 */
private fun barycentric(origin: P3, dir: P3, a: P3, b: P3, c: P3): Triple<Float, Float, Float>? {
    val e1x = b.x - a.x; val e1y = b.y - a.y; val e1z = b.z - a.z
    val e2x = c.x - a.x; val e2y = c.y - a.y; val e2z = c.z - a.z
    val px = dir.y * e2z - dir.z * e2y
    val py = dir.z * e2x - dir.x * e2z
    val pz = dir.x * e2y - dir.y * e2x
    val det = e1x * px + e1y * py + e1z * pz
    if (abs(det) < 0.00001f) return null
    val inv = 1f / det
    val tx = origin.x - a.x; val ty = origin.y - a.y; val tz = origin.z - a.z
    val u = (tx * px + ty * py + tz * pz) * inv
    if (u < -0.0005f || u > 1.0005f) return null
    val qx = ty * e1z - tz * e1y
    val qy = tz * e1x - tx * e1z
    val qz = tx * e1y - ty * e1x
    val v = (dir.x * qx + dir.y * qy + dir.z * qz) * inv
    if (v < -0.0005f || u + v > 1.0005f) return null
    return Triple(1 - u - v, u, v)
}

private fun pointOnFace(face: Face, bary: Triple<Float, Float, Float>): P3 {
    val (w0, w1, w2) = bary
    val a = face.world[0]; val b = face.world[1]; val c = face.world[2]
    return P3(
        a.x * w0 + b.x * w1 + c.x * w2,
        a.y * w0 + b.y * w1 + c.y * w2,
        a.z * w0 + b.z * w1 + c.z * w2,
    )
}

fun hitTest(
    faces: List<Face>,
    point: Offset,
    size: Int,
    cx: Float,
    cy: Float,
    scale: Float,
): FaceHit? {
    val distance = size * 3.4f
    val dir = rayFor(point, cx, cy, scale, distance)
    val origin = P3(0f, 0f, distance)
    for (face in faces.asReversed()) {
        val cs = face.cameraSpace
        for ((i, j, k) in listOf(Triple(0, 1, 2), Triple(0, 2, 3))) {
            val bary = barycentric(origin, dir, cs[i], cs[j], cs[k]) ?: continue
            val world = pointOnFace(face, bary)
            return FaceHit(face, world)
        }
    }
    return null
}

/** 把屏幕点投影到指定面所在的平面上，用于切割的终点。 */
fun projectOntoFace(
    face: Face,
    point: Offset,
    size: Int,
    cx: Float,
    cy: Float,
    scale: Float,
): P3? {
    val distance = size * 3.4f
    val dir = rayFor(point, cx, cy, scale, distance)
    val origin = P3(0f, 0f, distance)
    val a = face.cameraSpace[0]
    val b = face.cameraSpace[1]
    val c = face.cameraSpace[3]
    val e1x = b.x - a.x; val e1y = b.y - a.y; val e1z = b.z - a.z
    val e2x = c.x - a.x; val e2y = c.y - a.y; val e2z = c.z - a.z
    val nx = e1y * e2z - e1z * e2y
    val ny = e1z * e2x - e1x * e2z
    val nz = e1x * e2y - e1y * e2x
    val denom = dir.x * nx + dir.y * ny + dir.z * nz
    if (abs(denom) < 0.00001f) return null
    val t = ((a.x - origin.x) * nx + (a.y - origin.y) * ny + (a.z - origin.z) * nz) / denom
    if (t <= 0f) return null
    val hit = P3(origin.x + dir.x * t, origin.y + dir.y * t, origin.z + dir.z * t)
    // 在面平面上求局部坐标
    val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
    val wx = hit.x - a.x; val wy = hit.y - a.y; val wz = hit.z - a.z
    val e1Len2 = e1x * e1x + e1y * e1y + e1z * e1z
    val e2Len2 = vx * vx + vy * vy + vz * vz
    val u = (wx * e1x + wy * e1y + wz * e1z) / e1Len2
    val v = (wx * vx + wy * vy + wz * vz) / e2Len2
    val w0 = face.world[0]; val w1 = face.world[1]; val w3 = face.world[3]
    return P3(
        w0.x + u * (w1.x - w0.x) + v * (w3.x - w0.x),
        w0.y + u * (w1.y - w0.y) + v * (w3.y - w0.y),
        w0.z + u * (w1.z - w0.z) + v * (w3.z - w0.z),
    )
}


/** 体素级环境光遮蔽：凹槽内壁变暗，平面保持明亮。 */
private fun ambientOcclusion(voxels: Set<V3>, voxel: V3, dir: Dir): Float {
    val base = dir.corners(0, 0, 0)
    val u = base[1] - base[0]
    val v = base[3] - base[0]
    val outside = voxel + dir.normal
    val signs = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
    var total = 0f
    for ((sx, sy) in signs) {
        var occluders = 0
        if (outside + V3(u.x * sx, u.y * sx, u.z * sx) in voxels) occluders++
        if (outside + V3(v.x * sy, v.y * sy, v.z * sy) in voxels) occluders++
        if (outside + V3(u.x * sx + v.x * sy, u.y * sx + v.y * sy, u.z * sx + v.z * sy) in voxels) occluders++
        total += 0.64f + 0.12f * (3 - occluders)
    }
    return (total / 4f).coerceAtMost(1f)
}

private fun grain(v: V3, base: Color): Color {
    val h = ((v.x * 73856093) xor (v.y * 19349663) xor (v.z * 83492791)) and 0xFF
    val delta = (h % 9 - 4) / 100f
    return Color(
        (base.red * (1 + delta)).coerceIn(0f, 1f),
        (base.green * (1 + delta)).coerceIn(0f, 1f),
        (base.blue * (1 + delta)).coerceIn(0f, 1f),
        1f,
    )
}

fun DrawScope.drawVoxels(
    faces: List<Face>,
    base: Color,
    edge: Color = Color(0x22000000),
) {
    for (face in faces) {
        val color = lerp(Color(0xFF2A1B10), grain(face.voxel, base), face.shade)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(face.screen[0].x, face.screen[0].y)
            lineTo(face.screen[1].x, face.screen[1].y)
            lineTo(face.screen[2].x, face.screen[2].y)
            lineTo(face.screen[3].x, face.screen[3].y)
            close()
        }
        drawPath(path, color)
        drawPath(path, edge, style = Stroke(width = 0.8f))
    }
}

fun axisOfDelta(start: P3, end: P3): Triple<Int, Float, Float> {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dz = end.z - start.z
    val ax = abs(dx); val ay = abs(dy); val az = abs(dz)
    return when {
        ax >= ay && ax >= az -> Triple(0, dx, end.x)
        ay >= az -> Triple(1, dy, end.y)
        else -> Triple(2, dz, end.z)
    }
}

fun snapCut(value: Float, size: Int): Int? {
    val k = value.roundToInt()
    return if (k in 1 until size) k else null
}
