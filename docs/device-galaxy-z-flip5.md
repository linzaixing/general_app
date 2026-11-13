# 调试目标机：三星 Galaxy Z Flip5（SM-F731B）

> 实测日期：2026-11-13。数据来自设备 `getprop` / `/proc` / `dumpsys` / `sensorservice` 直接读取。

## 设备与系统

| 项目 | 信息 |
|---|---|
| 品牌 / 型号 | Samsung SM-F731B（Galaxy Z Flip5） |
| 产品代号 | b5q / b5qxeea（欧版） |
| 系统 | Android 16（SDK 36），One UI |
| 构建号 | BP4A.251205.006 / F731BXXS7GZG1 |
| 安全补丁 | 2026-07-05 |
| 内核 | Linux 5.15.189-android13（aarch64） |
| 芯片平台 | Qualcomm kalama（SM8550） |

## 处理器（SoC / CPU）

- **SoC**：高通骁龙 8 Gen 2 for Galaxy（SM8550，4nm）
- **CPU**：8 核，ARM big.LITTLE 三丛集

| 核心 | 架构 | 最高频率 |
|---|---|---|
| cpu7 | Cortex-X3（0xd4e）×1 | 3.36 GHz |
| cpu5–6 | Cortex-A710（0xd47）×2 | 2.80 GHz |
| cpu3–4 | Cortex-A715（0xd4d）×2 | 2.80 GHz |
| cpu0–2 | Cortex-A510（0xd46）×3 | 2.02 GHz |

- 架构：arm64-v8a

## 图形（GPU）

- **Adreno 740**（OpenGL ES 3.2，Vulkan 1.3，驱动 V@0676.77.1）
- 最高频率 719 MHz；可调频段：124.8 / 220 / 295 / 348 / 401 / 475 / 550 / 615 / 680 / 719 MHz

## NPU / AI

- **Hexagon NPU（HTP V73）**，系统内已加载 SNPE/QNN 运行库：
  `libSNPE.so`、`libqnnengine.so`、`libSnpeHtpV73Stub.so`、`libsnpe_dsp_domains_v3.so`
- 另有 `vendor.qti.hardware.dsp@1.0::IDspService`（ADSP/CDSP）在运行
- **注意**：Android 16 已移除 NNAPI，AI 推理需走 QNN / SNPE / LiteRT NPU 委托

## 内存与存储

- **RAM**：8 GB（`MemTotal` 7,345,652 kB ≈ 7.0 GiB），LPDDR5X
- **存储**：256 GB（`/data` 可用 222 GB，已用 16 GB / 8%），UFS 4.0

## 屏幕

- **内屏**：1080 × 2640，6.7"，480 dpi，LTPO 10–120 Hz（120/96/60/48/30/24/10），HDR10 / HLG / HDR10+
- **外屏**：720 × 748，3.4"，340 dpi，60 Hz

## 相机

- 共 4 个 camera device：后置 12 MP 广角 + 12 MP 超广角，前置 10 MP

## 传感器

- 六轴 IMU：STMicro **LSM6DSV**（加速度计 + 陀螺仪）
- 磁力计：AKM **AK09918**
- 气压计：STMicro **LPS22DF**
- 光线传感器：SensorTek
- 折叠角度 / 转轴角度、跌落检测、拾起手势、计步等 Samsung 传感器

## 电池

- 当前电量 84%，充电中，电压 4083 mV，温度 35.7 °C，健康状态良好
- 标称容量 3700 mAh（Samsung 未开放 sysfs 容量节点）

## 无线

- Wi-Fi 6（802.11ax），5 GHz / 160 MHz，当前协商速率 1921 Mbps（最高 2401 Mbps）
- 支持双路并发（`swlan0`）
- 蓝牙：LE Audio / ASHA / BAP 等（8 Gen 2 平台为 BT 5.3）

## USB OTG 带宽实测（接 Insta360 Air 双鱼眼相机）

> 实测日期：2026-11-14，设备 SM-F731B（无线 adb `192.168.1.5:34715`），相机 Insta360 Air。

### 链路层

| 项目 | 实测 / 规格 | 依据 |
|---|---|---|
| 手机 USB-C 口 | 官方规格 **USB 3.2 Gen 1（5 Gbps）**；xHCI SuperSpeed 根 hub 上报 `speed=10000`（控制器能力 10 Gbps） | Samsung 规格表 + `/sys/bus/usb/devices/usb3/speed` |
| 相机 | Insta360 Air，VID `0x2E1A` / PID `0x1000`，设备描述符 `version 2.00` | `/sys/bus/usb/devices/2-1/{idVendor,idProduct,version}` |
| 实际协商速率 | **480 Mbps（USB 2.0 High Speed）** | `/sys/bus/usb/devices/2-1/speed` |
| UVC 数据端点 | `EP 0x81` **Bulk**，wMaxPacketSize=512 B | `/sys/bus/usb/devices/2-1:1.1/ep_81` |
| UVC 事件端点 | `EP 0x83` Interrupt，64 B / 16 ms | `/sys/bus/usb/devices/2-1:1.0/ep_83` |
| USB2 HS bulk 可用吞吐 | 理论 53.3 MB/s（≈427 Mbps），实际常见 35–40 MB/s | USB 2.0 规范 |

结论：**手机端能力（≥5 Gbps）远高于相机（480 Mbps）**，链路速率完全由相机的 USB 2.0 接口决定，换 OTG 转接线/转接头不会更快；相机插在这台手机上永远跑 USB 2.0。

### 相机流与实测帧率

相机只提供 3 个 **MJPEG** 模式，YUYV 探针被直接拒绝（`setPreviewSize(..., FRAME_FORMAT_YUYV)` 抛异常）。用 `UVCCamera.setFrameCallback(..., PIXEL_FORMAT_RAW)` + 16×16 `ImageReader` 立即回收（避免 BufferQueue 反压）连续采集 8 秒：

| 模式 | 实测帧率 | 采样 |
|---|---|---|
| 3008×1504 | **29.4 fps** | 235 帧 / 8.0 s，逐帧抽样哈希全部不同（无重复帧） |
| 2176×1088 | 29.5 fps | 236 帧 / 8.0 s |
| 1472×736 | 29.5 fps | 236 帧 / 8.0 s |

- 三种模式都顶到相机的 **30 fps 上限** → USB 链路能完整承载 3008×1504@30fps 的 MJPEG，**没有因带宽不足掉帧**。
- 按 30 fps + USB2 可用上限反推：单帧 MJPEG ≤ ≈1.3 MB，码率约 **20–40 MB/s（160–320 Mbps）**，链路处于高负载但够用。
- libuvc 回调（`PIXEL_FORMAT_RAW`）给的是**解码后的 YUYV**（9,048,064 B/帧 = 3008×1504×2，`limit` 恒等于 buffer 容量），拿不到压缩后字节数，因此无法直接读出 MJPEG 码率，只能按「帧率 + USB2 上限」界定。

### 对 App 的影响

- App 端实时拼接预览约 **12–15 fps**（`enc`≈19 ms、WebView `round`≈40 ms），瓶颈在 CPU / WebView 往返，**不在 USB**。
- 相机只有 MJPEG，3008×1504 已是最高模式；若想降低预览开销，可只在取景时切 2176×1088，拍照/录制仍用 3008×1504（切模式要重启 UVC 流，画面会闪一下）。

## 对本工程的影响

| 约束 | 说明 |
|---|---|
| ABI | 只需 arm64-v8a；PanoCam `abiFilters 'arm64-v8a'` 已匹配 |
| 系统版本 | 目标机 Android 16（SDK 36），工程 targetSdk 34，需留意新版本行为变更 / 权限收紧 |
| AI 推理 | 不可用 NNAPI，改走 QNN / SNPE / LiteRT NPU 委托 |
| 折叠形态 | 内屏 LTPO 120 Hz 做预览；外屏仅 720×748 / 60 Hz，折叠态布局需单独适配；转轴角度传感器可驱动折叠态 UI |
| 性能基线 | Adreno 740 + 8 GB RAM，可作为 PanoCam 预览/录制帧率与内存（参考 `docs/pano-perf.md`）的基准机 |
| USB OTG | 相机链路固定 USB 2.0 HS 480 Mbps（相机侧限制），可满帧承载 3008×1504@30fps MJPEG；预览帧率瓶颈在 CPU 而非 USB |

## 实测结论（2026-11-13，PanoCam v1.37 / versionCode 38）

设备 `R5CWA08T7FV`，USB 调试已授权，`adb install -r` 正常。

| 项目 | 结果 |
|---|---|
| 安装 / 启动 | 正常，冷启动 ~543 ms，无 crash（`logcat -b crash` 空） |
| CAMERA 运行时授权 | 正常弹出系统授权框（GrantPermissionsActivity） |
| 首页「最近作品」 | 正常渲染（1080×2640，480 dpi，状态栏/导航栏无遮挡） |
| 取景页 | 可进入，`USBMonitor registered`，无 UVC 相机时正确显示「等待相机接入」 |
| 相册查询 | 走 MediaStore `Pictures/PanoCam`，仅能读到本应用自己写入的图片（未申请 `READ_MEDIA_IMAGES`，属设计如此） |
| 页面空闲检测 | `uiautomator dump` 报 `could not get idle state`：空状态球体动画持续刷新，属正常现象 |
| **16 KB 页对齐** | **不通过**。Android 16 弹出「应用兼容性」警告，列出 7 个 LOAD 段未对齐的 so：`libmmkv / libusb100 / libuvc / libUVCCamera / libnativelib / libpanostitch / libjpeg-turbo1500`。设备 `getconf PAGESIZE=4096`，实际可运行，但每次安装首次启动会提示，且 16 KB 页设备上会直接加载失败 |
| 折叠外屏 | 未验证：外屏熄屏，需物理折叠；可用 `am start --display 1` 触发，但需外屏点亮 |
| USB UVC 相机链路 | 未验证：无硬件 |

### 16 KB 对齐修复路径

1. 自有 `libpanostitch.so`：CMake 链接参数加 `-Wl,-z,max-page-size=16384`（NDK 25.2 的 lld 支持），AGP 8.5.2 会按 16 KB 对齐打包。
2. 第三方 6 个 `.so` 来自 `com.github.jiangdongguo.AndroidUSBCamera:libausbc/libuvc:3.2.7`，需升级到提供 16 KB 对齐产物的版本或自行编译；否则弹窗无法彻底消除。
3. 仅在本机（4 KB 页）开发时，弹窗选「不再显示」即可，不影响功能。

### 处理结果（v1.38 / versionCode 39）

| 项 | 状态 |
|---|---|
| 自有 `libpanostitch.so` | 已修复：`target_link_options(... -Wl,-z,max-page-size=16384)`，实测 `p_align=0x4000`，`zipalign -c -P 16` 通过（AGP 8.5.2 默认已按 16 KB 对齐未压缩 so，无需改 buildToolsVersion） |
| 其余 6 个 so | 仍为 4 KB（`p_align=0x1000`）。上游 `AndroidUSBCamera` 最新即 3.2.7，无 16 KB 版本 |
| 二进制改 p_align 方案 | 不可行：这些库各 LOAD 段的 `vaddr-offset` 为 0x1000/0x2000，不满足 `vaddr ≡ offset (mod 16K)`，强改会导致加载失败，必须重编 |
| 彻底修复 | 需自行用 16 KB 链接参数重编 libusb/libuvc/libUVCCamera/libjpeg-turbo/mmkv/nativelib，或换用提供 16 KB 产物的 UVC 库 |

结论：本机（4 KB 页）功能不受影响；若将来要跑在 16 KB 页设备上，须先解决上面 6 个第三方 so。

实测加载时机（`run-as ... /proc/<pid>/maps`）：6 个库均为懒加载——冷启动、进入取景页时都未映射，只有真正打开 UVC 相机 / 首次使用 MMKV 时才 `dlopen`。因此 16 KB 页设备上的表现是「App 能开、相册和预览正常，一接相机就失败」，而不是启动即崩。

v1.38 已装机验证：`install -r` 成功，冷启动正常无 crash；相册能读到真机拍摄的 2784×1392 成片，点卡片可预览；系统未再弹 16 KB 警告（此前已点过「不再显示」，故以 APK 内 `p_align=0x4000` + `zipalign -c -P 16` 通过为准）。

## 外屏（副屏）适配

外屏为系统私有显示（逻辑 display 1，748×720 @340dpi），第三方 App 不可枚举，需三星 MultiStar 白名单才能运行。完整踩坑记录、命令与正确路径见 [cover-screen-adaptation.md](cover-screen-adaptation.md)。
