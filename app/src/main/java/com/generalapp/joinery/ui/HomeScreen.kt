package com.generalapp.joinery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generalapp.joinery.domain.Level
import com.generalapp.joinery.domain.Levels
import com.generalapp.joinery.domain.ProgressSnapshot

@Composable
fun HomeScreen(progress: ProgressSnapshot, onOpen: (Level) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().background(Ink),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column {
                Text("榫卯匠造", color = Paper, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("以刀代笔，以木为纸", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("★", color = Brass, fontSize = 15.sp)
                    Text(
                        "  ${progress.totalStars} / ${progress.maxStars}",
                        color = Paper,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(
                        "已完成 ${progress.clearedIds.size} / ${Levels.all.size}",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        val chapters = Levels.all.groupBy { it.chapter }
        items(chapters.keys.sorted()) { chapter ->
            val levels = chapters.getValue(chapter)
            Column {
                Text(
                    levels.first().chapterTitle,
                    color = Brass,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                levels.chunked(4).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { level ->
                            LevelCard(
                                level = level,
                                stars = progress.starsOf(level.id),
                                unlocked = progress.isUnlocked(level.id),
                                modifier = Modifier.weight(1f),
                                onClick = { onOpen(level) },
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item {
            Text(
                "每一关的卯眼都在右侧。切掉多余木料，让左侧榫头与卯眼完全吻合。",
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun LevelCard(
    level: Level,
    stars: Int,
    unlocked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = when {
        !unlocked -> Panel.copy(alpha = 0.55f)
        stars > 0 -> PanelHigh
        else -> Panel
    }
    Column(
        modifier
            .height(86.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(
                1.dp,
                if (stars > 0) Brass.copy(alpha = 0.55f) else Color(0x22FFFFFF),
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = unlocked) { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!unlocked) {
            Text("锁", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Text("${level.id}", color = Muted, fontSize = 17.sp)
        } else {
            Text(
                level.title,
                color = Paper,
                fontSize = 12.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (stars > 0) "★".repeat(stars) else "未完成",
                color = if (stars > 0) Brass else Muted,
                fontSize = if (stars > 0) 12.sp else 10.sp,
            )
        }
    }
}
