package com.smartmood.app.domain

import java.time.LocalDate
import java.time.YearMonth

object Progress {

    /**
     * 当前连续打卡天数。今天还没打卡时，只要昨天打过，连续天数仍然保留。
     */
    fun currentStreak(dates: Set<LocalDate>, today: LocalDate): Int {
        var cursor = when {
            today in dates -> today
            today.minusDays(1) in dates -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        while (cursor in dates) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /** 历史上最长的连续打卡天数。 */
    fun longestStreak(dates: Set<LocalDate>): Int {
        if (dates.isEmpty()) return 0
        var best = 0
        for (date in dates) {
            if (date.minusDays(1) in dates) continue
            var length = 0
            var cursor = date
            while (cursor in dates) {
                length++
                cursor = cursor.plusDays(1)
            }
            if (length > best) best = length
        }
        return best
    }

    /** 已解锁的小精灵编号：按记录过的心情和历史最长连续天数计算。 */
    fun unlockedIds(entries: List<MoodEntry>): Set<String> {
        val moods = entries.map { it.mood }.toSet()
        val longest = longestStreak(entries.map { it.date }.toSet())
        return Creatures.all.filter { creature ->
            when (val rule = creature.rule) {
                is UnlockRule.FirstMood -> rule.mood in moods
                is UnlockRule.Streak -> longest >= rule.days
            }
        }.map { it.id }.toSet()
    }

    fun newlyUnlocked(before: Set<String>, after: Set<String>): List<Creature> =
        Creatures.all.filter { it.id in after && it.id !in before }

    fun moodCounts(entries: List<MoodEntry>, month: YearMonth): Map<Mood, Int> {
        val inMonth = entries.filter { YearMonth.from(it.date) == month }
        return Mood.entries.associateWith { mood -> inMonth.count { it.mood == mood } }
    }
}
