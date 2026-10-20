package com.generalapp.joinery.render

import androidx.compose.ui.geometry.Offset
import com.generalapp.joinery.domain.Levels
import com.generalapp.joinery.domain.Voxels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderTest {

    private val camera = Camera()

    @Test
    fun buildsFacesForAVisibleCube() {
        val faces = buildFaces(Voxels.fullCube(3), 3, camera, 200f, 200f, 40f)
        assertTrue(faces.isNotEmpty())
        assertTrue(faces.all { it.screen.all { p -> p.x.isFinite() && p.y.isFinite() } })
        assertTrue(faces.all { it.depth.isFinite() })
    }

    @Test
    fun hitTestAtCenterFindsAVoxel() {
        val model = Voxels.fullCube(3)
        val faces = buildFaces(model, 3, camera, 200f, 200f, 40f)
        val center = faces.last().screen.reduce { a, b -> a + b } / 4f
        val hit = hitTest(faces, center, 3, 200f, 200f, 40f)
        assertNotNull(hit)
        assertTrue(hit!!.face.voxel in model)
    }

    @Test
    fun midpointOfFrontFaceHitsThatFace() {
        val model = Voxels.fullCube(3)
        val faces = buildFaces(model, 3, camera, 200f, 200f, 40f)
        val front = faces.last()
        val center = front.screen.reduce { a, b -> a + b } / 4f
        val hit = hitTest(faces, center, 3, 200f, 200f, 40f)
        assertEquals(front.voxel, hit?.face?.voxel)
    }

    @Test
    fun projectingOntoAFaceReturnsAPointOnIt() {
        val model = Voxels.fullCube(3)
        val faces = buildFaces(model, 3, camera, 200f, 200f, 40f)
        val face = faces.last()
        val center = face.screen.reduce { a, b -> a + b } / 4f
        val world = projectOntoFace(face, center, 3, 200f, 200f, 40f)
        assertNotNull(world)
        val w = world!!
        assertTrue(w.x in -2f..2f && w.y in -2f..2f && w.z in -2f..2f)
    }

    @Test
    fun outsidePointHitsNothing() {
        val faces = buildFaces(Voxels.fullCube(3), 3, camera, 200f, 200f, 40f)
        assertEquals(null, hitTest(faces, Offset(-500f, -500f), 3, 200f, 200f, 40f))
    }

    @Test
    fun firstLevelIsSolvableWithThreeStars() {
        val level = Levels.byId(1)!!
        var voxels = level.initial
        voxels = Voxels.cut(voxels, 0, 3, removeGreater = true)
        voxels = Voxels.cut(voxels, 0, 1, removeGreater = false)
        voxels = Voxels.cut(voxels, 1, 3, removeGreater = true)
        voxels = Voxels.cut(voxels, 1, 1, removeGreater = false)
        voxels = Voxels.cut(voxels, 2, 2, removeGreater = false)
        assertEquals(level.target, voxels)
        assertEquals(3, Levels.starsFor(level.par, 5))
    }
}
