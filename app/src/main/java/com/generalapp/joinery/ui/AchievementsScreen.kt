package com.generalapp.joinery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.generalapp.joinery.domain.Achievement
import com.generalapp.joinery.domain.Achievements
import com.generalapp.joinery.domain.ProgressSnapshot

@Composable
fun AchievementsScreen(progress: ProgressSnapshot) {
    val unlocked = progress.achievements()
    LazyColumn(
        Modifier.fillMaxSize().background(Ink),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("木作印记", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text("已达成 ${unlocked.size} / ${Achievements.all.size}", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.size(8.dp))
            }
        }
        items(Achievements.all) { achievement ->
            AchievementRow(achievement, achievement.id in unlocked)
        }
    }
}

@Composable
private fun AchievementRow(achievement: Achievement, unlocked: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (unlocked) Panel else Panel.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (unlocked) Brass.copy(alpha = 0.4f) else Color(0x14FFFFFF),
                RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (unlocked) "◆" else "◇",
            color = if (unlocked) Brass else Muted,
            fontSize = 18.sp,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                achievement.title,
                color = if (unlocked) Paper else Muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                achievement.description,
                color = Muted,
                fontSize = 12.sp,
            )
        }
        if (unlocked) Text("已达成", color = Brass, fontSize = 11.sp)
    }
}
