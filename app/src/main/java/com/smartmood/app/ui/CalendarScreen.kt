package com.smartmood.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartmood.app.AppViewModel
import com.smartmood.app.domain.Mood
import com.smartmood.app.domain.MoodEntry
import com.smartmood.app.domain.Progress
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun CalendarScreen(vm: AppViewModel) {
    var month by remember { mutableStateOf(YearMonth.from(vm.today)) }
    var picked by remember { mutableStateOf<MoodEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val byDate = vm.entries.associateBy { it.date }
    val counts = Progress.moodCounts(vm.entries, month)
    val dates = vm.entries.map { it.date }.toSet()

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("心情日历", fontSize = 26.sp, fontWeight = FontWeight.Bold)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("上个月") }
            Text("${month.year}年${month.monthValue}月", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(vm.today)) { Text("下个月") }
        }

        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color(0xFF7A6A5A))
            }
        }

        val leading = month.atDay(1).dayOfWeek.let { (it.value - DayOfWeek.MONDAY.value + 7) % 7 }
        val cells: List<LocalDate?> = List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.height((((cells.size + 6) / 7) * 52).dp),
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(cells) { date ->
                if (date == null) {
                    Spacer(Modifier.aspectRatio(1f))
                } else {
                    val entry = byDate[date]
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .background(
                                entry?.let { Color(it.mood.color) } ?: if (date == vm.today) Color(0xFFE9E1D5) else Color.White,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(enabled = entry != null) { picked = entry },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${date.dayOfMonth}",
                            color = if (entry != null) Color.White else Ink,
                            fontWeight = if (date == vm.today) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本月心情分布", fontWeight = FontWeight.SemiBold)
                val total = counts.values.sum().coerceAtLeast(1)
                Mood.entries.forEach { mood ->
                    val count = counts[mood] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(Color(mood.color), CircleShape))
                        Text("  ${mood.label}", Modifier.weight(0.25f))
                        Box(
                            Modifier
                                .weight(0.6f)
                                .height(8.dp)
                                .background(Color(0xFFF1E9DE), RoundedCornerShape(4.dp)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(count.toFloat() / total)
                                    .height(8.dp)
                                    .background(Color(mood.color), RoundedCornerShape(4.dp)),
                            )
                        }
                        Text("$count 天", Modifier.weight(0.15f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        }

        Text(
            "累计打卡 ${vm.entries.size} 天，历史最长连续 ${Progress.longestStreak(dates)} 天",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF7A6A5A),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, vm.exportText())
                    }
                    context.startActivity(Intent.createChooser(send, "导出心情记录"))
                },
                enabled = vm.entries.isNotEmpty(),
            ) { Text("导出记录") }
            OutlinedButton(onClick = { confirmClear = true }, enabled = vm.entries.isNotEmpty()) { Text("清除全部数据") }
        }
        Text("所有记录只保存在这台手机上，不会上传。", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7A6A5A))
    }

    picked?.let { entry ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("${entry.date.monthValue}月${entry.date.dayOfMonth}日 · ${entry.mood.label}") },
            text = { Text(entry.note.ifBlank { "这一天没有留言" }) },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("关闭") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部数据？") },
            text = { Text("心情记录和已解锁的伙伴会被删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    confirmClear = false
                }) { Text("确认清除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}
