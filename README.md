# SmartMood

一个只在手机本地运行的「心情打卡 + 收集小精灵 + AR 互动」安卓应用。
没有账号、没有服务器、不联网，所有记录保存在手机里。

## 功能

| 页面 | 内容 |
|---|---|
| 今日 | 选一种心情打卡，可写最多 80 字留言；同一天可修改。显示当前连续天数。 |
| 日历 | 月视图，每天用当天心情的颜色标记；下方显示当月各心情次数。 |
| 图鉴 | 10 只原创小精灵。未解锁显示剪影和获得条件，解锁后可选为 AR 伙伴。 |
| AR | 用相机识别桌面/地面，点击放置小精灵（最多 5 只），点它有弹跳反应；可拍照存到相册 `Pictures/SmartMood`。 |

### 解锁规则

- 首次记录某种心情 → 解锁与该心情对应的小精灵（6 只）。
- 历史最长连续打卡 ≥ 3 / 7 / 14 / 30 天 → 各解锁 1 只（共 4 只）。

## 构建

需要 JDK 17 与 Android SDK（platform 37、build-tools 34 或更高）。

```bash
export JAVA_HOME=/opt/sdkman/candidates/java/17.0.15-tem
export ANDROID_HOME=/home/opencode/android-sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

安装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 技术说明

- Kotlin + Jetpack Compose，minSdk 29 / targetSdk 36 / compileSdk 37。
- AR 使用 SceneView 4.60 + ARCore；3D 小精灵由球体、圆锥等基础几何体在代码中拼出，不含任何外部美术资源。
- 数据：SQLite（打卡记录）+ SharedPreferences（选中的伙伴），无网络请求，无第三方统计。
- 权限：仅相机。依赖库默认声明的联网、震动权限已在清单中显式移除。
- AR 是可选的：设备不支持 ARCore 时，其余功能照常使用，AR 页会给出提示。

## 已验证 / 未验证

已验证：

- 单元测试 12 项全部通过（连续天数、最长连续、解锁阈值、解锁不丢失、心情统计、数据完整性等）。
- Debug APK 构建成功，包名 `com.smartmood.app`，清单权限仅 `CAMERA`。

未验证：

- 真机 AR 效果。当前环境没有安卓真机或模拟器，平面识别、放置位置、3D 造型观感都只做了逻辑与编译验证，需要在支持 ARCore 的手机上实测。
- 相机权限弹窗、拍照写入相册等系统交互，同样需要真机确认。
