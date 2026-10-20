package com.generalapp.joinery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.generalapp.joinery.ui.AchievementsScreen
import com.generalapp.joinery.ui.Brass
import com.generalapp.joinery.ui.CodexScreen
import com.generalapp.joinery.ui.HomeScreen
import com.generalapp.joinery.ui.Ink
import com.generalapp.joinery.ui.JoineryTheme
import com.generalapp.joinery.ui.Muted
import com.generalapp.joinery.ui.Panel
import com.generalapp.joinery.ui.PuzzleScreen

class MainActivity : ComponentActivity() {

    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JoineryTheme {
                JoineryApp(viewModel)
            }
        }
    }
}

@Composable
private fun JoineryApp(vm: GameViewModel) {
    val level = vm.level
    if (level != null) {
        BackHandler { vm.closeLevel() }
        PuzzleScreen(vm = vm, level = level, onExit = { vm.closeLevel() })
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("关卡", "图鉴", "印记")

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Text(
                                when (index) {
                                    0 -> "关"
                                    1 -> "鉴"
                                    else -> "印"
                                },
                                color = if (tab == index) Brass else Muted,
                                fontSize = 16.sp,
                            )
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color(0x22D8A84E),
                            selectedTextColor = Brass,
                            unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> HomeScreen(vm.progress) { vm.open(it) }
                1 -> CodexScreen(vm.progress)
                else -> AchievementsScreen(vm.progress)
            }
        }
    }
}
