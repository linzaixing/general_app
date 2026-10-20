package com.smartmood.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartmood.app.AppViewModel
import com.smartmood.app.domain.Creatures
import com.smartmood.app.domain.Mood
import com.smartmood.app.domain.Progress
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

@Composable
fun CheckInScreen(vm: AppViewModel, onOpenCollection: () -> Unit) {
    val today = vm.todayEntry()
    var editing by remember(today?.date, vm.loaded) { mutableStateOf(today == null) }
    var selected by remember(today?.mood) { mutableStateOf(today?.mood) }
    var note by remember(today?.note) { mutableStateOf(today?.note.orEmpty()) }
    val streak = Progress.currentStreak(vm.entries.map { it.date }.toSet(), vm.today)

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(vm.today.format(dateFormat), style = MaterialTheme.typography.labelLarge, color = Accent)
        Text(
            if (today == null || editing) "此刻，你的心情是？" else "今天已经记录好了",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (streak > 0) "已连续打卡 $streak 天" else "从今天开始，遇见第一只小精灵",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF7A6A5A),
        )

        if (!vm.loaded) return@Column

        if (editing) {
            MoodGrid(selected) { selected = it }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(AppViewModel.NOTE_LIMIT) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("想说的话（可选）") },
                supportingText = { Text("${note.length}/${AppViewModel.NOTE_LIMIT}") },
                minLines = 2,
                maxLines = 4,
            )
            Button(
                onClick = {
                    selected?.let {
                        vm.checkIn(it, note)
                        editing = false
                    }
                },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (today == null) "完成打卡" else "保存修改") }
        } else if (today != null) {
            TodayCard(today.mood, today.note) { editing = true }
        }

        val partner = vm.selectedCreature()
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().clickable { onOpenCollection() },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CreatureArt(partner, size = 72.dp)
                Spacer(Modifier.size(14.dp))
                Column {
                    Text("今日伙伴：${partner.name}", fontWeight = FontWeight.SemiBold)
                    Text(partner.story, style = MaterialTheme.typography.bodySmall, color = Color(0xFF7A6A5A))
                }
            }
        }
    }

    UnlockDialog(vm)
}

@Composable
private fun MoodGrid(selected: Mood?, onSelect: (Mood) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.height(190.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false,
    ) {
        items(Mood.entries.toList()) { mood ->
            val isSelected = mood == selected
            Card(
                onClick = { onSelect(mood) },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(mood.color).copy(alpha = 0.22f) else Color.White,
                ),
                border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) Color(mood.color) else MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(28.dp).background(Color(mood.color), CircleShape))
                    Text(mood.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun TodayCard(mood: Mood, note: String, onEdit: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(mood.color).copy(alpha = 0.18f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).background(Color(mood.color), CircleShape))
                Spacer(Modifier.size(10.dp))
                Text(mood.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (note.isNotBlank()) Text(note)
            OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(12.dp)) { Text("修改今天的记录") }
        }
    }
}

@Composable
private fun UnlockDialog(vm: AppViewModel) {
    val fresh = vm.justUnlocked
    if (fresh.isEmpty()) return
    AlertDialog(
        onDismissRequest = { vm.consumeUnlocked() },
        confirmButton = { TextButton(onClick = { vm.consumeUnlocked() }) { Text("太好了") } },
        title = { Text(if (fresh.size == 1) "遇见新伙伴" else "遇见 ${fresh.size} 位新伙伴") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fresh.forEach { creature ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CreatureArt(creature, size = 64.dp)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(creature.name, fontWeight = FontWeight.Bold)
                            Text(creature.story, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("可以在图鉴里选为 AR 伙伴，放进你的房间。", style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
fun CollectionScreen(vm: AppViewModel, onOpenAr: () -> Unit) {
    val unlocked = vm.unlockedIds
    val partner = vm.selectedCreature()
    var detailId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("心情图鉴", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("已遇见 ${unlocked.size} / ${Creatures.all.size}", color = Color(0xFF7A6A5A))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(Creatures.all) { creature ->
                val isUnlocked = creature.id in unlocked
                Card(
                    onClick = { detailId = creature.id },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(
                        if (creature.id == partner.id) 2.dp else 1.dp,
                        if (creature.id == partner.id) Accent else MaterialTheme.colorScheme.outline,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CreatureArt(creature, size = 92.dp, locked = !isUnlocked)
                        Text(if (isUnlocked) creature.name else "？？？", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isUnlocked) (if (creature.id == partner.id) "当前 AR 伙伴" else "点击查看") else creature.unlockHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF7A6A5A),
                        )
                    }
                }
            }
        }
    }

    val detail = Creatures.byId(detailId)
    if (detail != null) {
        val isUnlocked = detail.id in unlocked
        AlertDialog(
            onDismissRequest = { detailId = null },
            title = { Text(if (isUnlocked) detail.name else "尚未遇见") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CreatureArt(detail, size = 120.dp, locked = !isUnlocked)
                    Text(if (isUnlocked) detail.story else detail.unlockHint)
                }
            },
            confirmButton = {
                if (isUnlocked) {
                    TextButton(onClick = {
                        vm.selectCreature(detail.id)
                        detailId = null
                        onOpenAr()
                    }) { Text("选为 AR 伙伴并放置") }
                } else {
                    TextButton(onClick = { detailId = null }) { Text("知道了") }
                }
            },
            dismissButton = if (isUnlocked) {
                { TextButton(onClick = { detailId = null }) { Text("关闭") } }
            } else {
                null
            },
        )
    }

    LaunchedEffect(Unit) { vm.consumeUnlocked() }
}
