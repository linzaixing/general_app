package com.generalapp.joinery.domain

data class Level(
    val id: Int,
    val chapter: Int,
    val chapterTitle: String,
    val title: String,
    val size: Int,
    val target: Set<V3>,
    val par: Int,
    val joint: JointType,
    val hint: String,
) {
    val initial: Set<V3> get() = Voxels.fullCube(size)
    val mortise: Set<V3> get() = Voxels.fullCube(size) - target
    val freeCubes: Int get() = initial.size - target.size
}

object Levels {
    private const val C1 = "第一章 · 直榫入门"
    private const val C2 = "第二章 · 燕尾与格肩"
    private const val C3 = "第三章 · 粽角与穿插"

    val all: List<Level> = listOf(
        Level(
            1, 1, C1, "方直榫", 4,
            Voxels.box(4, 1..2, 1..2, 2..3), 7, JointType.STRAIGHT,
            "卯眼是正中的方孔。先切掉四周的木料，再修平端面。",
        ),
        Level(
            2, 1, C1, "长榫", 4,
            Voxels.box(4, 1..2, 0..1, 1..3), 8, JointType.STRAIGHT,
            "榫头偏下，注意保留底部的两根木料。",
        ),
        Level(
            3, 1, C1, "双层榫", 4,
            Voxels.box(4, 1..2, 1..2, 1..3), 9, JointType.STRAIGHT,
            "榫头贯穿三层，切到卯眼深度时不要多切。",
        ),
        Level(
            4, 1, C1, "宽肩直榫", 4,
            Voxels.box(4, 1..2, 1..2, 2..3) + Voxels.box(4, 0..0, 1..2, 3..3) +
                Voxels.box(4, 3..3, 1..2, 3..3), 9, JointType.SHOULDER,
            "两侧各留一条肩，肩面要和卯眼外沿齐平。",
        ),
        Level(
            5, 2, C2, "燕尾榫", 4,
            Voxels.box(4, 1..2, 1..2, 2..2) + Voxels.box(4, 0..2, 1..2, 3..3), 8, JointType.DOVETAIL,
            "端部比根部宽，先把最外层的梯形切出来。",
        ),
        Level(
            6, 2, C2, "燕尾双齿", 4,
            Voxels.box(4, 1..2, 0..3, 2..2) +
                Voxels.box(4, 0..2, 0..1, 3..3) + Voxels.box(4, 0..2, 2..3, 3..3), 10, JointType.DOVETAIL,
            "两枚榫齿之间留着空隙，切割时注意不要伤到中间的卯壁。",
        ),
        Level(
            7, 2, C2, "格肩榫", 4,
            Voxels.box(4, 1..2, 1..2, 1..3) + Voxels.box(4, 0..0, 1..2, 3..3), 9, JointType.SHOULDER,
            "一侧带肩，肩部只保留最外层一格。",
        ),
        Level(
            8, 2, C2, "双格肩", 4,
            Voxels.box(4, 1..2, 1..2, 1..3) +
                Voxels.box(4, 0..0, 1..2, 3..3) + Voxels.box(4, 3..3, 1..2, 3..3), 10, JointType.SHOULDER,
            "两侧对称留肩，先切上下，再切左右。",
        ),
        Level(
            9, 3, C3, "粽角榫", 4,
            Voxels.box(4, 0..1, 0..1, 2..2) +
                (Voxels.box(4, 0..2, 0..0, 3..3) + Voxels.box(4, 0..0, 0..2, 3..3)), 9, JointType.CORNER,
            "角部的三向咬合，先保住角上的木料，再清掉多余部分。",
        ),
        Level(
            10, 3, C3, "十字搭接", 4,
            Voxels.box(4, 1..1, 0..3, 3..3) + Voxels.box(4, 0..3, 1..1, 3..3), 8, JointType.CROSS,
            "纵横各一条，交叉处共用一格，别把它凿掉。",
        ),
        Level(
            11, 3, C3, "楔钉榫", 4,
            Voxels.box(4, 1..1, 1..2, 0..3) + Voxels.box(4, 0..0, 1..1, 2..3), 9, JointType.WEDGE,
            "细长的榫舌最怕切过头，靠近榫舌时改用单块凿除。",
        ),
        Level(
            12, 3, C3, "穿带榫", 4,
            Voxels.box(4, 0..3, 1..1, 2..3) + Voxels.box(4, 0..3, 2..2, 3..3), 9, JointType.THROUGH,
            "贯通的长槽，先把两端切通，再修中间的槽壁。",
        ),
        Level(
            13, 3, C3, "走马销", 4,
            Voxels.box(4, 0..1, 1..1, 2..3) + Voxels.box(4, 2..2, 1..2, 3..3), 9, JointType.SLIDE,
            "燕尾状的销子从侧面推入，窄处进、宽处锁。",
        ),
    )

    fun byId(id: Int): Level? = all.firstOrNull { it.id == id }

    fun starsFor(par: Int, actions: Int): Int = when {
        actions <= par -> 3
        actions <= par + 3 -> 2
        else -> 1
    }
}
