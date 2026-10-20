package com.generalapp.joinery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxelsTest {

    @Test
    fun fullCubeHasExpectedCount() {
        assertEquals(27, Voxels.fullCube(3).size)
        assertEquals(64, Voxels.fullCube(4).size)
    }

    @Test
    fun cutRemovesGreaterSide() {
        val cube = Voxels.fullCube(3)
        val after = Voxels.cut(cube, axis = 0, k = 1, removeGreater = true)
        assertEquals(9, after.size)
        assertTrue(after.all { it.x == 0 })
    }

    @Test
    fun cutRemovesLowerSide() {
        val cube = Voxels.fullCube(3)
        val after = Voxels.cut(cube, axis = 1, k = 2, removeGreater = false)
        assertEquals(9, after.size)
        assertTrue(after.all { it.y >= 2 })
    }

    @Test
    fun cutNeverEmptiesThePiece() {
        val cube = Voxels.fullCube(3)
        val after = Voxels.cut(cube, axis = 2, k = 3, removeGreater = false)
        assertEquals(cube, after)
    }

    @Test
    fun chiselKeepsAtLeastOneVoxel() {
        val single = setOf(V3(1, 1, 1))
        assertEquals(single, Voxels.removeOne(single, V3(1, 1, 1)))
    }

    @Test
    fun cutResultCanBeEmptyChecked() {
        val two = setOf(V3(0, 0, 0), V3(1, 0, 0))
        val after = Voxels.cut(two, axis = 0, k = 0, removeGreater = false)
        assertEquals(2, after.size)
    }

    @Test
    fun boxClipsToBounds() {
        val b = Voxels.box(3, 2..5, 0..0, 0..0)
        assertEquals(1, b.size)
        assertTrue(V3(2, 0, 0) in b)
        assertFalse(V3(3, 0, 0) in b)
    }

    @Test
    fun connectivityDetectsSplitShapes() {
        val split = setOf(V3(0, 0, 0), V3(2, 2, 2))
        assertFalse(Voxels.connected(split))
        assertTrue(Voxels.connected(Voxels.box(4, 0..1, 0..1, 0..1)))
    }
}
