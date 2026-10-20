package com.generalapp.joinery.domain

data class Achievement(val id: String, val title: String, val description: String)

object Achievements {
    val all = listOf(
        Achievement("first", "第一刀", "完成第一个榫卯。"),
        Achievement("five", "渐入佳境", "完成 5 个榫卯。"),
        Achievement("all", "木作大成", "完成全部关卡。"),
        Achievement("perfect3", "严丝合缝", "在 3 个关卡中获得三星。"),
        Achievement("perfectAll", "分毫不差", "全部关卡获得三星。"),
        Achievement("noWaste", "惜木如金", "用不超过目标步数完成任意关卡。"),
    )
}

data class ProgressSnapshot(val stars: Map<Int, Int>) {
    val clearedIds: Set<Int> get() = stars.filterValues { it > 0 }.keys
    val totalStars: Int get() = stars.values.sum()
    val maxStars: Int get() = Levels.all.size * 3

    fun starsOf(id: Int): Int = stars[id] ?: 0

    fun isUnlocked(id: Int): Boolean = id == 1 || starsOf(id - 1) > 0

    fun unlockedJoint(joint: JointType): Boolean =
        Levels.all.any { it.joint == joint && starsOf(it.id) > 0 }

    fun achievements(): Set<String> = buildSet {
        val cleared = clearedIds
        if (cleared.isNotEmpty()) add("first")
        if (cleared.size >= 5) add("five")
        if (cleared.size == Levels.all.size) add("all")
        if (cleared.count { starsOf(it) == 3 } >= 3) add("perfect3")
        if (cleared.size == Levels.all.size && cleared.all { starsOf(it) == 3 }) add("perfectAll")
        if (cleared.any { id -> Levels.byId(id)?.let { starsOf(id) <= it.par } == true }) add("noWaste")
    }
}
