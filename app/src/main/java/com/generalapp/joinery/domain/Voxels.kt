package com.generalapp.joinery.domain

data class V3(val x: Int, val y: Int, val z: Int) {
    operator fun plus(other: V3) = V3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: V3) = V3(x - other.x, y - other.y, z - other.z)
    fun coord(axis: Int) = when (axis) {
        0 -> x
        1 -> y
        else -> z
    }
    fun withCoord(axis: Int, value: Int) = when (axis) {
        0 -> copy(x = value)
        1 -> copy(y = value)
        else -> copy(z = value)
    }
}

object Voxels {
    val neighbors = listOf(
        V3(1, 0, 0), V3(-1, 0, 0),
        V3(0, 1, 0), V3(0, -1, 0),
        V3(0, 0, 1), V3(0, 0, -1),
    )

    fun fullCube(size: Int): Set<V3> =
        buildSet {
            for (x in 0 until size) for (y in 0 until size) for (z in 0 until size) add(V3(x, y, z))
        }

    fun box(size: Int, xr: IntRange, yr: IntRange, zr: IntRange): Set<V3> =
        buildSet {
            for (x in xr) for (y in yr) for (z in zr) {
                if (x in 0 until size && y in 0 until size && z in 0 until size) add(V3(x, y, z))
            }
        }

    fun connected(voxels: Set<V3>): Boolean {
        if (voxels.isEmpty()) return true
        val start = voxels.first()
        val seen = mutableSetOf(start)
        val queue = ArrayDeque<Int>()
        queue.add(0)
        val list = voxels.toList()
        val index = list.withIndex().associate { it.value to it.index }
        while (queue.isNotEmpty()) {
            val v = list[queue.removeFirst()]
            for (n in neighbors) {
                val next = v + n
                if (next in voxels && seen.add(next)) queue.add(index.getValue(next))
            }
        }
        return seen.size == voxels.size
    }

    fun removeOne(voxels: Set<V3>, target: V3): Set<V3> =
        if (voxels.size <= 1) voxels else voxels - target

    /** 沿某个轴向的整平面切割：移除坐标大于（或小于）k 的所有体素。 */
    fun cut(voxels: Set<V3>, axis: Int, k: Int, removeGreater: Boolean): Set<V3> {
        val kept = voxels.filterNot { v ->
            val c = v.coord(axis)
            if (removeGreater) c >= k else c < k
        }.toSet()
        return if (kept.isEmpty()) voxels else kept
    }

    fun matches(voxels: Set<V3>, target: Set<V3>) = voxels == target
}
