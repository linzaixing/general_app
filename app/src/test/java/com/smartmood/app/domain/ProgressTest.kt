package com.smartmood.app.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {

    private val today = LocalDate.of(2026, 10, 20)

    private fun entry(day: LocalDate, mood: Mood = Mood.CALM) = MoodEntry(day, mood, "")

    @Test
    fun streakIsZeroWithoutRecentEntries() {
        assertEquals(0, Progress.currentStreak(emptySet(), today))
        assertEquals(0, Progress.currentStreak(setOf(today.minusDays(2)), today))
    }

    @Test
    fun streakCountsConsecutiveDaysEndingToday() {
        val dates = setOf(today, today.minusDays(1), today.minusDays(2))
        assertEquals(3, Progress.currentStreak(dates, today))
    }

    @Test
    fun streakIsKeptWhenOnlyYesterdayIsDone() {
        val dates = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, Progress.currentStreak(dates, today))
    }

    @Test
    fun streakStopsAtGap() {
        val dates = setOf(today, today.minusDays(1), today.minusDays(3))
        assertEquals(2, Progress.currentStreak(dates, today))
    }

    @Test
    fun longestStreakFindsBestRun() {
        val dates = setOf(
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
            LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11),
            LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13),
        )
        assertEquals(4, Progress.longestStreak(dates))
        assertEquals(0, Progress.longestStreak(emptySet()))
    }

    @Test
    fun firstMoodUnlocksMatchingCreature() {
        val unlocked = Progress.unlockedIds(listOf(entry(today, Mood.JOY)))
        assertTrue("nuantuan" in unlocked)
        assertFalse("kunkun" in unlocked)
    }

    @Test
    fun streakCreaturesUnlockAtThresholds() {
        val three = (0L..2L).map { entry(today.minusDays(it)) }
        assertTrue("lianlianya" in Progress.unlockedIds(three))
        assertFalse("manyuedeng" in Progress.unlockedIds(three))

        val seven = (0L..6L).map { entry(today.minusDays(it)) }
        assertTrue("manyuedeng" in Progress.unlockedIds(seven))
    }

    @Test
    fun unlockedCreaturesStayUnlockedAfterStreakBreaks() {
        val oldRun = (20L..22L).map { entry(today.minusDays(it)) }
        assertTrue("lianlianya" in Progress.unlockedIds(oldRun))
    }

    @Test
    fun newlyUnlockedReturnsOnlyTheDifference() {
        val before = setOf("nuantuan")
        val after = setOf("nuantuan", "jinghu")
        assertEquals(listOf("jinghu"), Progress.newlyUnlocked(before, after).map { it.id })
    }

    @Test
    fun moodCountsOnlyIncludeRequestedMonth() {
        val entries = listOf(
            entry(LocalDate.of(2026, 10, 1), Mood.JOY),
            entry(LocalDate.of(2026, 10, 2), Mood.JOY),
            entry(LocalDate.of(2026, 10, 3), Mood.SAD),
            entry(LocalDate.of(2026, 9, 30), Mood.JOY),
        )
        val counts = Progress.moodCounts(entries, YearMonth.of(2026, 10))
        assertEquals(2, counts[Mood.JOY])
        assertEquals(1, counts[Mood.SAD])
        assertEquals(0, counts[Mood.CALM])
    }

    @Test
    fun everyCreatureHasUniqueId() {
        val ids = Creatures.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(10, ids.size)
    }

    @Test
    fun moodFromUnknownIdFallsBackToCalm() {
        assertEquals(Mood.CALM, Mood.fromId("does-not-exist"))
    }
}
