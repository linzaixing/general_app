package com.smartmood.app.domain

import java.time.LocalDate

enum class Mood(val id: String, val label: String, val color: Long) {
    JOY("joy", "开心", 0xFFF6B93B),
    CALM("calm", "平静", 0xFF5DADE2),
    TIRED("tired", "疲惫", 0xFF9B8EC4),
    ANXIOUS("anxious", "焦虑", 0xFFEB7F4B),
    SAD("sad", "低落", 0xFF6C8EA4),
    EXCITED("excited", "期待", 0xFFE86A92);

    companion object {
        fun fromId(id: String): Mood = entries.firstOrNull { it.id == id } ?: CALM
    }
}

data class MoodEntry(
    val date: LocalDate,
    val mood: Mood,
    val note: String,
)

enum class Shape { ROUND, TALL, FLAT, DROP, SPIKY, PETAL }

enum class Eyes { OPEN, SLEEPY, HAPPY }

sealed interface UnlockRule {
    data class FirstMood(val mood: Mood) : UnlockRule
    data class Streak(val days: Int) : UnlockRule
}

data class Creature(
    val id: String,
    val name: String,
    val story: String,
    val body: Long,
    val accent: Long,
    val shape: Shape,
    val eyes: Eyes,
    val rule: UnlockRule,
) {
    val unlockHint: String
        get() = when (val r = rule) {
            is UnlockRule.FirstMood -> "第一次记录「${r.mood.label}」时遇见"
            is UnlockRule.Streak -> "连续打卡 ${r.days} 天后遇见"
        }
}

object Creatures {
    val all: List<Creature> = listOf(
        Creature(
            "nuantuan", "暖团子", "总是鼓着脸颊的小团子，喜欢陪你把开心的事再回味一遍。",
            0xFFF6B93B, 0xFFF08A7E, Shape.ROUND, Eyes.HAPPY, UnlockRule.FirstMood(Mood.JOY),
        ),
        Creature(
            "jinghu", "静湖", "安静地站在你身边，像一面不起波纹的小湖。",
            0xFF7FB8E6, 0xFFBEE0F5, Shape.TALL, Eyes.OPEN, UnlockRule.FirstMood(Mood.CALM),
        ),
        Creature(
            "kunkun", "困困豆", "走两步就想躺下。它想提醒你：先休息，再出发。",
            0xFFB4A7D6, 0xFFE6DFF5, Shape.FLAT, Eyes.SLEEPY, UnlockRule.FirstMood(Mood.TIRED),
        ),
        Creature(
            "chanchan", "颤颤毛", "一紧张就会抖毛。靠近它，一起慢慢深呼吸。",
            0xFFF0955E, 0xFFFFD8B8, Shape.SPIKY, Eyes.OPEN, UnlockRule.FirstMood(Mood.ANXIOUS),
        ),
        Creature(
            "yudizai", "雨滴仔", "低落的日子里出现的小水滴，不说话，但会一直在。",
            0xFF86A8BD, 0xFFD0E2EC, Shape.DROP, Eyes.SLEEPY, UnlockRule.FirstMood(Mood.SAD),
        ),
        Creature(
            "xinghuo", "星火球", "对明天充满好奇，眼睛亮晶晶的。",
            0xFFEE7FA5, 0xFFFFC9DC, Shape.PETAL, Eyes.HAPPY, UnlockRule.FirstMood(Mood.EXCITED),
        ),
        Creature(
            "lianlianya", "连连芽", "每天浇一点点水，就会悄悄长高。",
            0xFF7CC47F, 0xFFC9EBC6, Shape.ROUND, Eyes.OPEN, UnlockRule.Streak(3),
        ),
        Creature(
            "manyuedeng", "满月灯", "一周的坚持点亮了它，肚子里有一盏小灯。",
            0xFFF3D36B, 0xFFFFF1B8, Shape.TALL, Eyes.HAPPY, UnlockRule.Streak(7),
        ),
        Creature(
            "caihongtuan", "彩虹团", "把你这半个月的心情都收进了身体里。",
            0xFF8E7CE0, 0xFFF4A6C8, Shape.PETAL, Eyes.HAPPY, UnlockRule.Streak(14),
        ),
        Creature(
            "shouhuzhe", "守护者", "三十天的陪伴，它会一直守着你的小角落。",
            0xFFEFE9DE, 0xFFC9B79A, Shape.SPIKY, Eyes.OPEN, UnlockRule.Streak(30),
        ),
    )

    fun byId(id: String?): Creature? = all.firstOrNull { it.id == id }
}
