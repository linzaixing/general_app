package com.smartmood.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.smartmood.app.ui.Accent
import com.smartmood.app.ui.ArScreen
import com.smartmood.app.ui.CalendarScreen
import com.smartmood.app.ui.CheckInScreen
import com.smartmood.app.ui.CollectionScreen
import com.smartmood.app.ui.Paper
import com.smartmood.app.ui.SmartMoodTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartMoodTheme {
                SmartMoodApp(viewModel)
            }
        }
    }
}

private val tabs = listOf("今日", "日历", "图鉴", "AR")

@Composable
private fun SmartMoodApp(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { TabIcon(index, tab == index) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFE5F0F0)),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> CheckInScreen(vm, onOpenCollection = { tab = 2 })
                1 -> CalendarScreen(vm)
                2 -> CollectionScreen(vm, onOpenAr = { tab = 3 })
                else -> ArScreen(vm.selectedCreature())
            }
        }
    }
}

@Composable
private fun TabIcon(index: Int, selected: Boolean) {
    val color = if (selected) Accent else Color(0xFF8A7B6B)
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val stroke = Stroke(width = w * 0.09f)
        when (index) {
            0 -> {
                drawCircle(color, radius = w * 0.4f, style = stroke)
                drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.37f, w * 0.43f))
                drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.63f, w * 0.43f))
                drawArc(color, 20f, 140f, false, Offset(w * 0.32f, w * 0.40f), Size(w * 0.36f, w * 0.30f), style = stroke)
            }
            1 -> {
                drawRoundRect(color, Offset(w * 0.1f, w * 0.18f), Size(w * 0.8f, w * 0.72f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = stroke)
                drawLine(color, Offset(w * 0.1f, w * 0.4f), Offset(w * 0.9f, w * 0.4f), strokeWidth = w * 0.09f)
            }
            2 -> {
                drawCircle(color, radius = w * 0.18f, center = Offset(w * 0.3f, w * 0.32f), style = stroke)
                drawCircle(color, radius = w * 0.18f, center = Offset(w * 0.7f, w * 0.32f), style = stroke)
                drawCircle(color, radius = w * 0.18f, center = Offset(w * 0.5f, w * 0.7f), style = stroke)
            }
            else -> {
                drawRoundRect(color, Offset(w * 0.08f, w * 0.26f), Size(w * 0.84f, w * 0.58f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = stroke)
                drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.5f, w * 0.55f), style = stroke)
            }
        }
    }
}
