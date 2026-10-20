package com.generalapp.joinery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {

    @Test
    fun firstLevelIsAlwaysUnlocked() {
        val progress = ProgressSnapshot(emptyMap())
        assertTrue(progress.isUnlocked(1))
        assertFalse(progress.isUnlocked(2))
    }

    @Test
    fun clearingLevelUnlocksTheNextOne() {
        val progress = ProgressSnapshot(mapOf(1 to 2))
        assertTrue(progress.isUnlocked(2))
        assertFalse(progress.isUnlocked(3))
    }

    @Test
    fun totalStarsSumsBestResults() {
        val progress = ProgressSnapshot(mapOf(1 to 3, 2 to 1))
        assertEquals(4, progress.totalStars)
    }

    @Test
    fun achievementsUnlockInOrder() {
        assertTrue(ProgressSnapshot(emptyMap()).achievements().isEmpty())

        val first = ProgressSnapshot(mapOf(1 to 3)).achievements()
        assertTrue("first" in first)
        assertTrue("noWaste" in first)

        val five = ProgressSnapshot((1..5).associateWith { 3 }).achievements()
        assertTrue("five" in five)
        assertTrue("perfect3" in five)
    }

    @Test
    fun codexUnlocksWithJointLevels() {
        val straightLevel = Levels.all.first { it.joint == JointType.STRAIGHT }
        val progress = ProgressSnapshot(mapOf(straightLevel.id to 1))
        assertTrue(progress.unlockedJoint(JointType.STRAIGHT))
        assertFalse(progress.unlockedJoint(JointType.DOVETAIL))
    }

    @Test
    fun allStarsUnlocksFinalAchievement() {
        val progress = ProgressSnapshot(Levels.all.associate { it.id to 3 })
        val achievements = progress.achievements()
        assertTrue("all" in achievements)
        assertTrue("perfectAll" in achievements)
    }
}
