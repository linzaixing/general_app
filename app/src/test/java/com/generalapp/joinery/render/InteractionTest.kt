package com.generalapp.joinery.render

import androidx.compose.ui.geometry.Offset
import com.generalapp.joinery.domain.Levels
import com.generalapp.joinery.domain.Voxels
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionTest {

    @Test
    fun horizontalDragProducesASingleSidedCut() {
        val level = Levels.byId(1)!!
        val cam = Camera()
        val cx = 200f; val cy = 200f; val scale = 55f
        val faces = buildFaces(level.initial, level.size, cam, cx, cy, scale)
        val front = faces.filter { it.normal.z == 1 }.maxByOrNull { it.depth }
        assertNotNull(front)

        val start = front!!.screen.reduce { a, b -> a + b } / 4f
        val end = start + Offset(90f, 0f)
        val hit = hitTest(faces, start, level.size, cx, cy, scale)
        assertNotNull(hit)
        val endWorld = projectOntoFace(hit!!.face, end, level.size, cx, cy, scale)
        assertNotNull(endWorld)

        val (axis, delta, coord) = axisOfDelta(hit.world, endWorld!!)
        assertTrue("drag should have a direction", kotlin.math.abs(delta) > 0.3f)
        val k = snapCut(coord + level.size / 2f, level.size)
        assertTrue("cut line must land inside the block", k in 1 until level.size)

        val after = Voxels.cut(level.initial, axis, k, delta > 0)
        val removed = level.initial - after
        assertTrue(removed.isNotEmpty())
        assertTrue(after.isNotEmpty())
        assertTrue(
            "removed voxels must all sit on the dragged side",
            removed.all { v -> if (delta > 0) v.coord(axis) >= k else v.coord(axis) < k },
        )
    }
}
