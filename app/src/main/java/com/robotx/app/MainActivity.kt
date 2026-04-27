package com.robotx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.robotx.app.ui.CameraScreen
import com.robotx.app.ui.HomeScreen
import com.robotx.app.ui.theme.RobotXTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RobotXTheme {
                RobotXNavHost()
            }
        }
    }
}

@Composable
private fun RobotXNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenCamera = { nav.navigate("camera") }
            )
        }
        composable("camera") {
            CameraScreen(
                onClose = { nav.popBackStack() }
            )
        }
    }
}
