package com.generalapp.joinery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsTest {

    @Test
    fun everyLevelIsValid() {
        assertTrue(Levels.all.isNotEmpty())
        for (level in Levels.all) {
            val cube = Voxels.fullCube(level.size)
            assertTrue("target must be inside the block: ${level.id}", cube.containsAll(level.target))
            assertTrue("target must not be empty: ${level.id}", level.target.isNotEmpty())
            assertTrue("target must be connected: ${level.id}", Voxels.connected(level.target))
            assertTrue(
                "target must reach the opening face: ${level.id}",
                level.target.any { it.z == level.size - 1 },
            )
            assertTrue("mortise must not be empty: ${level.id}", level.mortise.isNotEmpty())
            assertTrue("par must be positive: ${level.id}", level.par > 0)
        }
    }

    @Test
    fun idsAreUniqueAndSequential() {
        val ids = Levels.all.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals((1..Levels.all.size).toList(), ids)
    }

    @Test
    fun everyJointHasAtLeastOneLevel() {
        for (joint in JointType.entries) {
            assertNotNull(
                "joint without level: $joint",
                Levels.all.firstOrNull { it.joint == joint },
            )
        }
    }

    @Test
    fun starsFollowPar() {
        assertEquals(3, Levels.starsFor(8, 8))
        assertEquals(2, Levels.starsFor(8, 11))
        assertEquals(1, Levels.starsFor(8, 12))
    }

    @Test
    fun chaptersAreOrdered() {
        val chapters = Levels.all.map { it.chapter }
        assertEquals(chapters.sorted(), chapters)
    }
}
