package com.robotx.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.robotx.app.ui.WelcomeScreen
import com.robotx.app.ui.arm.ArmMainScreen
import com.robotx.app.ui.theme.RobotXTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 挖孔屏（相机孔）也照常铺满：enableEdgeToEdge 在部分版本上不设 cutout 模式，
        // 这里显式声明 SHORT_EDGES（仅 P+ 支持），主画面才能延伸进挖孔两侧与顶端。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setContent {
            RobotXTheme {
                ImmersiveFullScreen()
                RobotXNavHost()
            }
        }
    }
}

/**
 * 两页的层层递进：欢迎/引导页 → 控制台。
 *
 * 进入控制台有两条路，靠 `preview` 参数区分：
 *   * 连上机械臂服务（通道 ONLINE）→ `preview=false`，正常控制；
 *   * 首页点「跳过，先看控制台」→ `preview=true`，**离线预览**：
 *     界面与本地 3D 仿真照常看，但使能 / 方向 / 急停一律拦下并提示先联网。
 *
 * 旧 fly_agent 的聊天首页与相机页已删除，本工程只沿用它的框架
 * （Compose + 主题 + DataStore + OkHttp），不保留其原有功能。
 */
@Composable
private fun RobotXNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "welcome") {
        composable("welcome") {
            LockOrientation(landscape = false)
            WelcomeScreen(
                onConnected = { nav.navigate("arm_main") { launchSingleTop = true } },
                onSkip = { nav.navigate("arm_main?preview=true") { launchSingleTop = true } },
            )
        }
        composable(
            route = "arm_main?preview={preview}",
            arguments = listOf(
                navArgument("preview") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            LockOrientation(landscape = true)
            ArmMainScreen(
                preview = entry.arguments?.getBoolean("preview") == true,
                onBack = { nav.popBackStack("welcome", inclusive = false) },
            )
        }
    }
}

/**
 * 欢迎页竖屏、控制台横屏 —— 控制台要横屏是因为左侧要放相机/仿真画面、右侧要放方向盘，
 * 竖屏下两边都会被压得没法用。
 *
 * Activity 已声明 `configChanges="orientation|screenSize|..."`，切方向不会重建 Activity。
 */
@Composable
private fun LockOrientation(landscape: Boolean) {
    val context = LocalContext.current
    DisposableEffect(landscape) {
        (context as? Activity)?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose { }
    }
}

/**
 * 默认全屏：隐藏状态栏与导航栏，把整块屏幕让给画面和方向盘。
 *
 * 行为用 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`：需要看时间/电量时，
 * 从屏幕边缘划一下，两个栏会短暂浮出来几秒再自动收起 —— 不用退出全屏、也不留常驻占位。
 *
 * ⚠️ 两处容易踩的坑：
 *   1. 只调 `enableEdgeToEdge()` 是不够的，它只是把栏变透明，仍然占位且常驻可见；
 *      真正「隐藏」必须走 `WindowInsetsControllerCompat.hide()`。
 *   2. 从别的 App 切回来、或锁屏解锁后，系统会把栏放回来 ——
 *      所以在 `ON_RESUME` 重新隐藏一次，否则「全屏」只在冷启动那一下成立。
 */
@Composable
private fun ImmersiveFullScreen() {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(view, lifecycleOwner) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        fun hideSystemBars() {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        hideSystemBars()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hideSystemBars()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}