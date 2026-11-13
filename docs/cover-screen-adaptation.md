# 三星 Z Flip5（SM-F731B）外屏适配实录：卡点与解决方案

> 记录日期：2026-11-14。目标：让 PanoCam 在折叠态外屏（副屏）上运行/显示预览。
> 结论先行：**第三方 App 无法自己上外屏，必须走三星 MultiStar 白名单**。下面按踩坑顺序列出。

## 环境

| 项 | 值 |
|---|---|
| 设备 | SM-F731B（Galaxy Z Flip5，b5qxeea），Android 16 / SDK 36 |
| 内屏 | display 0，1080×2640，480 dpi，LTPO 10–120 Hz |
| 外屏 | 逻辑 display **1**，748×720，340 dpi，60 Hz，SurfaceFlinger display id `4630947181303254916` |
| 外屏标志 | `FLAG_PRESENTATION / FLAG_EXTRA_BUILT_IN_DISPLAY / FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS`，`canHostTasks=true` |
| 外屏宿主 | `com.android.systemui/.subscreen.SubHomeActivity`（`SECONDARY_HOME`） |
| 调试方式 | 无线 adb `192.168.1.5:34715`（USB 也可） |

---

## 卡点 1：调试包卡在 “Waiting For Debugger”

**现象**：`am start` 后黑屏 15 s，logcat 里 `Application com.example.panocam is waiting for the debugger`。

**原因**：三星开发者选项里「等待调试器」是开的（`settings global debug_app=com.example.panocam`、`wait_for_debugger=1`），所有 debug 包启动即挂起。

**解决**：

```powershell
adb shell am clear-debug-app
adb shell settings put global wait_for_debugger 0
```

## 卡点 2：Android 16 的 16 KB 页对齐弹窗

**现象**：首次启动弹「应用兼容性 / 16 KB 对齐失败」，列出 7 个 so 的 LOAD 段未对齐；弹窗期间所有点击无效（看起来像“按钮点不动”）。

**判定**：`getconf PAGESIZE = 4096`，本机可正常运行；弹窗只是提醒，点「不再显示」即可。

**自有库修复**（`libpanostitch`）：

```cmake
target_link_options(panostitch PRIVATE -Wl,-z,max-page-size=16384)
```

验证：ELF `p_align=0x4000`，且整包 `zipalign -c -P 16` 通过（AGP 8.5.2 默认已对未压缩 so 做 16 KB 打包对齐，**不需要**改 buildToolsVersion）。

**第三方库无法修**：其余 6 个来自 `AndroidUSBCamera:libausbc/libuvc:3.2.7`（jitpack 最新），仍是 4 KB。且各 LOAD 段的 `vaddr-offset = 0x1000/0x2000`，不满足 `vaddr ≡ offset (mod 16K)`，**强改 p_align 会导致加载失败**，只能重编或换库。

**影响面**：这 6 个库是懒加载（冷启动、进取景页都未映射，接 UVC 相机时才 `dlopen`）。所以 16 KB 页设备上的表现是「App 能开、相册正常，一插相机就崩」，比启动崩溃更隐蔽。

## 卡点 3：想“合盖自动切到外屏”，代码侧做不到

尝试过：监听 `Sensor.TYPE_HINGE_ANGLE`（type 36），角度 ≤120° 时用 `ActivityOptions.setLaunchDisplayId()` 把自己搬到外屏。实测连续失败，原因有两条：

1. **Activity 先被暂停，传感器随后注销**
   logcat 时序：`hinge=90.0`（还收得到）→ `onPause display=0`（内屏熄）→ 之后再无任何 hinge 事件。
   即合盖过程中 App 很快失去前台状态，靠 Activity 内的传感器兜不住；且此时再 `startActivity` 会撞上后台启动限制。
2. **第三方 App 根本枚举不到副屏**
   `DisplayManager.getDisplays()` 对普通 App 只返回 `0:1080x2402`，日志会打印 `no cover display`。副屏是系统私有显示，`FLAG_PRESENTATION` 不对第三方开放，`setLaunchDisplayId(1)` 无从谈起。

> 这条路径已从代码中回退，不要重复尝试。

**唯一例外**：`adb shell am start --display 1 ...` 可以（shell uid 有跨显示权限），可用来验证 UI 在外屏的显示效果，但不能作为产品方案。

## 卡点 4：系统白名单只有几款应用

设置里确实有原生入口：

- 页面：`com.android.settings/.Settings$AppAllowedCoverScreenSettingsActivity`
- Action：`com.samsung.settings.APPS_ALLOWED_COVER_SCREEN`
- 相关设置键：`settings get system apps_allowed_on_cover_screen`（总开关，`large_cover_screen_apps` 类似）

但该页列表是**硬编码白名单**（实测：地图、微信、信息、YouTube），PanoCam 不会出现。页面底部写明：「下载并安装 MultiStar，以便在外屏上显示更多应用程序」，并提供「获取 MultiStar」按钮。

## 卡点 5：MultiStar 的坑

1. **版本必须匹配固件**
   - 9.0.15（minSdk 35）→ 启动即提示「该软件版本不支持MultiStar。请更新到最新软件。」
   - **10.3.29（minSdk 36）可用**，versionCode `1032900000`
2. **没有桌面图标**，只能靠 action 启动：
   ```powershell
   adb shell am start -a com.samsung.android.multistar.action.SETTINGS
   ```
3. 真正的设置页 `com.samsung.android.coverstar.ui.apps.AppsLauncherSettingsActivity` **未导出**，`am start -n` 会被 `SecurityException: not exported` 拒绝，只能从 MultiStar UI 层层点进去。
4. APK 来源：Galaxy Store 需登录三星账号；本次改从镜像站下载（16.2 MB / 29.2 MB），安装前用 `apksigner verify --print-certs` 确认签名 `CN=Samsung Cert, O=Samsung Corporation`，并核对页面公布的 SHA-256。

## 正确路径（可复现）

1. 安装 MultiStar 10.3.29（或更高）
2. 打开 MultiStar → **I ❤️ Galaxy Foldable** → **外屏小组件 / 应用启动器**
3. 打开「启用应用启动器」→ 在应用网格里选中 **PanoCam**
4. 合盖 → 外屏滑到应用列表 → 点 PanoCam 启动

配套可选：设置 → 外屏 → 「外屏可用的应用」页（`com.samsung.settings.APPS_ALLOWED_COVER_SCREEN`），MultiStar 开启后该页会作为补充入口。

## 卡点 6：USB 授权弹窗永远出现在内屏（折叠时无法确认）

**现象**：App 已在外屏运行，插入 UVC 相机后 `UsbPermissionActivity` 全部落在 display 0（内屏），且内屏熄屏时 `isSleeping=true`、`isVisible=false`，用户看不到 → 权限永远拿不到，App 反复重试。

**根因**：
1. 该弹窗由 system_server 发起（`launchedFromUid=1000`），未指定 display，固定落到默认显示（内屏），App 无法改变。
2. 我们的 `device_filter.xml` 里 VID 写的是 `11786 (0x2E0A)`，而真机相机是 `11802 (0x2E1A)`，系统无法把它和我们的 USB intent-filter 关联，也就无法建立「默认打开应用」偏好，只能走运行时授权。

**解决（已生效）**：
1. `device_filter.xml` 补上 `vendor-id="11802"`，让系统的 `device_attached_activities` 能匹配到我们的 Activity。
2. 展开手机，在首次授权弹窗里勾选 **「总是允许」** → 系统写入 `dumpsys usb` 的 `profile_group_settings.device_preferences`（按 VID/PID/class + 包名），持久化在 `/data/system/users/0/usb_device_manager.xml`，重启保留。
3. 之后每次插入：`UsbProfileGroupSettingsManager.deviceAttached()` 命中偏好 → 直接 `grantDevicePermission(uid)`，**不再创建弹窗**；App 侧 `hasPermission()` 立即为 true，直接开流。

**验证**：`adb shell dumpsys usb` 能看到 `device_preferences`（Insta360 Air, vendor 11802/pid 4096）与 `permissions_manager` 里 `uids=10340`；logcat 只有 `USBMonitor registered → openDevice ok`，没有 `requestUsbPermission`。

**会失效的场景**：换不同 VID/PID 的相机（需再勾一次）；卸载重装/清除应用数据（偏好随包清理）；不同用户或工作资料各自独立。

## 外屏 UI 注意事项

- 外屏 748×720 @340 dpi，系统会套 `FlipLargeCoverScreenSizeCompatMode`（`mScale=0.5`），部分应用会被整体缩小
- 实测 PanoCam 直接跑在外屏时布局完整（状态栏、取景提示、拼接按钮都正常），因为是 `MATCH_PARENT` + dp 自适应
- 外屏仅 60 Hz，预览帧率上限按 60 处理；功耗高于内屏
- 折叠时内屏熄灭会触发 Activity pause → 相机会释放，重新点亮要重新初始化，属预期行为

## 常用调试命令

```powershell
# 多屏截图（注意用 SurfaceFlinger display id，而不是逻辑 id 0/1）
adb shell screencap -d 4630947181303254916 -p /sdcard/cover.png

# 查显示器状态/尺寸/标志
adb shell dumpsys display | Select-String "mDisplayId=|DisplayDeviceInfo|state (ON|OFF|DOZE)"

# 把 App 投到外屏（仅验证用）
adb shell am start --display 1 -n com.example.panocam/.MainActivity -f 0x10000000

# 查当前楼层与任务所在显示
adb shell dumpsys activity activities | Select-String "topResumedActivity|Task\{.*panocam|mLaunchDisplayId"

# 查折叠角度（0=合盖，180=展开）
adb shell dumpsys sensorservice | Select-String "hinge"
```

## 顺手记下的其它坑

| 现象 | 说明 |
|---|---|
| `uiautomator dump` 报 `could not get idle state` | 首页空状态有持续动画，属正常；改用 `dumpsys` 或直接截图 |
| 首页/设置里的点击“没反应” | 先排除 16 KB 兼容性弹窗、USB 授权弹窗等系统层遮挡 |
| `adb shell input` 坐标偏移 | 截图是整屏像素（1080×2640），按截图比例换算即可；底部 2496–2640 属手势区，别点 |
