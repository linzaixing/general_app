package com.robotx.app.ui.arm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robotx.app.arm.model.CameraDto
import com.robotx.app.arm.model.JointDto
import com.robotx.app.arm.transport.ArmEndpoint
import com.robotx.app.ui.theme.hudFrame

/** 左侧窗口的两个画面：相机 / 仿真。语音不占选项卡，入口在方向轮盘左上角的麦克风键。 */
private enum class ViewTab(val label: String) {
    CAMERA("相机"),
    SIM("仿真"),
}

/**
 * 左侧「一窗两画面」：相机实时画面与 URDF 仿真共用同一块窗口区域，靠选项卡切换。
 *
 * 窗口本身有意做窄 —— 右手的方向盘才是常用操作，屏幕横向空间优先给它。
 *
 * 2026-10-08（v1.0.12）：外框由 Card 换成 `Surface + hudFrame` 的荧光切角框（直角），
 * 与右侧操作台共用同一套 HUD 语法；选项卡改等宽 + 荧光选中色，未选中压成灰，
 * 让「现在在看哪一路画面」在第一眼就分得开。
 */
@Composable
fun ArmViewWindow(
    joints: List<JointDto>,
    camera: CameraDto,
    endpoint: ArmEndpoint?,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(ViewTab.CAMERA.name) }
    val current = ViewTab.entries.firstOrNull { it.name == tab } ?: ViewTab.CAMERA

    Surface(
        modifier = modifier.hudFrame(),
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 选项卡自带的下划线指示器用的是 `primary`，正好就是这套的荧光色，所以不用自定义 indicator。
            TabRow(
                selectedTabIndex = current.ordinal,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                ViewTab.entries.forEach { t ->
                    val on = current == t
                    Tab(
                        selected = on,
                        onClick = { tab = t.name },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Text(
                                text = t.label,
                                fontSize = 12.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                letterSpacing = 1.sp,
                            )
                        },
                    )
                }
            }

            // 切换时对应视图按需挂载/卸载：页面与 mesh 都在 assets 本地，重建在亚秒级。
            Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(6.dp)) {
                when (current) {
                    ViewTab.CAMERA -> ArmCameraView(
                        endpoint = endpoint,
                        camera = camera,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ViewTab.SIM -> UrdfView(joints = joints, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}