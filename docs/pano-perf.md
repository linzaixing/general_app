# 全景相机性能优化记录

> 记录双鱼眼全景相机（Insta360 Air / UVC）从 v1.20 到 v1.30 的性能摸底、优化手段、实测数据与回退原因，便于后续追溯。
> 所有数据来自真机 `PanoPerf` 日志（每 5 秒聚合，值为 p50/p95，单位 ms）。

## 1. 测试口径

| 项 | 值 |
|---|---|
| 设备 | CET-AL00（Android 12 / arm64-v8a / 屏幕 1224×2446） |
| 采集源 | v1.31 起画质优先取 **3008×1504**（v1.30 及以前自动回退 2176×1088；相机无 2560×1280 档） |
| 预览档 | 640×320 / 1024×512 / **1536×768**（v1.26 起默认 1536） |
| 保存档 | 1024×512 / 2048×1024 / **2784×1392**（v1.31 起画质优先，默认 2784） |
| 拼接内核 | v1.24 起 NEON（`libpanostitch.so`）；v1.30 起预览优先 GPU，自检失败自动回退 NEON |
| 日志 tag | `PanoPerf` |

> 注意：页面里的「预览 1536」是 **WebView 画布的宽度上限**。竖屏下画布实际是 1536×3070（约 470 万像素），这是 1536 档比 1024 档慢一倍多的直接原因。

## 2. 埋点字段

| 字段 | 含义 |
|---|---|
| `grab` | `TextureView.getBitmap` 回读（UI 线程） |
| `stitch` | 拼接总耗时（GPU 成功时等于 `gpu`） |
| `gpu` | GPU 拼接：纹理上传 + 片元着色 + 回读（v1.30） |
| `enc` | Bitmap 拷贝 + JPEG + base64 |
| `push` | `pushFrame` 提交耗时（基本为 0，只是赋值） |
| `round` | 帧推给页面 → 页面解码并上传纹理完成（含 poll 节流等待） |
| `frame` | 单帧 native 端到端 |
| `fps` / `frames` | 窗口内有效帧率与帧数 |
| `jpgKB` | 单帧 JPEG 体积 |
| `recpush` / `recstart` / `recstop` | 录制：送帧 / 启动 / 收尾 |
| `p_grab` / `p_stitch` / `p_rotate` / `p_png` / `p_io` / `p_all` | 拍照分段 |
| `js decode/upload/draw/raf` | 网页侧：dataURL 解码、texImage2D、WebGL 提交、rAF 间隔 |

## 3. 基线（v1.20，1024 预览 / 2048 保存）

```
fps 8.7   grab 14–19   stitch 17–18   enc 10   round 53–58   frame 42–48
录制 2.5–3.1fps（grab 45–73 / stitch 165–180 / enc 48–59 / frame 290–326）
拍照 3.4–4.2s（p_stitch 1240–1305 / p_png 1455–2053）
```

## 4. 优化清单

| 版本 | 手段 | 实测收益 |
|---|---|---|
| v1.21 | 预览轮询节流 55ms → 8ms | `round` 53–58 → 21 |
| v1.21 | LUT 双槽缓存（预览 / 保存各一份） | 拍照 `p_stitch` 1.2s → 0.2s |
| v1.21 | 拍照 PNG → JPEG(q95) | `p_png` 1.5–2.0s → 0.04s |
| v1.22 | `rotateEquirect` 按行多线程 | `p_rotate` 690–750 → 87–145 |
| v1.23 | 预览回读按预览宽度（1024 档读 1024×512） | `grab` 17–19 → 12–15 |
| v1.23 | 拍照复用输出缓冲 | 少分配 8–13MB / 次 |
| v1.23 | 录制时预览每 2 帧推一次 | 录制 `enc` 开销减半 |
| v1.24 | NEON 拼接内核（JNI + CMake，自检回退 Java） | 2048：72→40；2560：122→53 |
| v1.26 | 录制码率上限 8 → 28Mbps | 画质改善（非性能） |
| v1.27 | 删除 5 个页面功能、退出按钮移入侧栏 | 无性能变化 |
| v1.30 | GPU 拼接（EGL + 片元着色器，自检回退） | 1536 档 `gpu` 5.7ms（NEON 同档 18–20ms） |

综合（v1.20 → v1.24，1024/2048 同口径）：预览 **8.7 → 15–16fps**，2048 录制 **2.9 → 9fps**，拍照 **4.1s → 0.3s**。

## 5. 分辨率与光学上限

按 `PanoStitcher.OFFSET`（origin 3008×1504）推导：

- 镜头参数：镜片半径 r ≈ 766.6 / 781.6 px，视场 200°（半视场 100° = 1.745 rad）
- 角分辨率 ≈ r / 1.745 ≈ **439–448 px/rad**
- 等距图 360°：`2π × 443 ≈ 2784 px`；180°：`π × 443 ≈ 1392 px`

| 采集源 | 每镜头 | 光学上限（拼接后 2:1） |
|---|---|---|
| 3008×1504 | 1504×1504 | **≈2784×1392** |
| 2176×1088 | 1088×1088 | ≈2000×1000 |
| 1472×736 | 736×736 | ≈1360×680 |

结论：保存档超过光学上限只是插值放大（4096×2048 已被移除）；2176 源下 2560 档约 28% 插值，2048 档最贴合。

## 6. 关键结论

1. **回读是结构性瓶颈**：`getBitmap` 在 2176 源下 15ms，3008 源会到 70–100ms，且随分辨率线性增长。
2. **预览编码 + 回传** 是第二瓶颈：1536 档 `enc` 20ms、`round` 36ms（poll 11 + decode 8 + upload 16）。
3. **CPU 拼接已到顶**：NEON 2176→2048 约 34ms，无法支撑 30fps；GPU 后降到 5.7ms。
4. **拍照已不是瓶颈**：端到端 0.28–0.33s，剩余为 `p_rotate` 与 `p_io`。

## 7. 已回退 / 失败尝试（追溯）

| 尝试 | 现象 | 处理 |
|---|---|---|
| GPU 首版采样未加 0.5 像素 | `selfTest maxErr=180 bad=9305`，`meanDiff=6.3`；赤道点 B 通道 149 vs 254 | 加 `(uv + 0.5)/size` → `maxErr=70`、`meanDiff=1.0` |
| GPU 越界未回黑 | 剩余 `maxErr=70 bad=659`，集中在镜头圆边界（GPU 钳到纹理边缘，可能读到另一颗镜头） | 增加 `samp()`：越界返回黑，与 CPU `sample()` 等价 → `maxErr=2 bad=0` |
| 采集 3008 探针 | 相机不支持 2560×1280，`pickSize` 自动回退 2176×1088 | 采集档决策待定 |

## 8. 当前状态（v1.30，2176×1088 采集 / 1536 预览）

```
fps=10.4  grab=15.4  gpu=5.7  enc=20.2  round=35.7  frame=42.2  jpgKB=168
录制：仍走 CPU/NEON（未接 GPU 直出）
拍照：0.3s 级
```

## 9. 下一步计划

- **采集档已决策（v1.31）**：画质优先，固定 3008×1504；保存默认 2784×1392。

1. **P0 第二步**：自有 EGL 接管相机 `SurfaceTexture`
   - 预览：屏幕正常绘制；等距图 FBO 只回读预览尺寸（1536×768，约 5–6ms）
   - 录制：等距图直接渲染进 `MediaCodec` 输入 Surface（去掉回读与 CPU 拼接）
   - 预览与录制解耦（录制全帧，预览每 2 帧推一次）
   - 目标：1536 预览 18–20fps；录制接近采集帧率
2. **P1 预览达标 30fps**：JPEG q75 / 1280 纹理上采样；`createImageBitmap` 离主线程解码；PBO 回读
3. **P2**：拍照 `rotateEquirect` 上 NEON（87–145 → ~40ms）；UVC 帧率探测（`Size.intervals` → fps）

## 10. 复测方法

```powershell
adb shell am force-stop com.example.panocam
adb logcat -c
Start-Process adb -ArgumentList 'logcat','-v','threadtime','-s','PanoPerf:I' `
  -RedirectStandardOutput 'F:\vibe_coding\general_app\gpu.txt' -NoNewWindow
```

进入全景页停留 10–60 秒（自检只在进程启动后第一次进入时执行）。重点看：

- `gpu selfTest maxErr=... bad=.../8192`、`bench gpu stitch=ok/off`
- 每 5 秒 `win=... frames=... fps=... grab/stitch/gpu/enc/round/frame`
- 拍照窗口的 `p_*`、录制窗口的 `rec*`

## 11. v1.32（自有 EGL 接管，实测数据）

改动（`GlPreview` 新增，`EquirectRecorder` 增加 direct 模式）：

- 相机帧直接进自建 `SurfaceTexture`，**彻底去掉 `TextureView.getBitmap` 的 UI 线程整帧回读**（原来 grab≈15ms）。
- GL 线程每来一个新相机帧就：`updateTexImage` → 原始鱼眼画屏 → （录制时）等距图直出编码器 Surface。
- 预览等距图渲染进 FBO 后**只回读 1536×768**（原来是 2176×1088 回读 + GPU 上传纹理）。
- 录制零回读：着色器直接画进 `MediaCodec` 输入 Surface，`eglPresentationTimeANDROID` 用真实时间戳（视频按真实时长播放）。
- 预览在录制时降到每 4 帧推一次；`roll`（防抖）在着色器里烘焙，预览不再做 CPU 旋转。
- 启动时用合成图对 GPU 拼接做逐像素自检（`gpu selfTest maxErr=2 bad=0/8192`），失败自动退到 NEON + 旧遥控路径。

实测（CET-AL00 / Adreno 6xx，3008×1504 采集，1536×768 预览，全屏查看器；单位为 ms）：

| 场景 | fps | grab | gpu | read | enc | frame | jpgKB | 备注 |
|---|---|---|---|---|---|---|---|---|
| v1.30 基线（2176 采集 / 1536 预览） | 10.4 | 15.4 | 5.7 | – | 20.2 | 42.2 | 168 | `getBitmap` 回读 + 整帧上传纹理 |
| v1.32 满血（前 20 秒） | **28.7~29.7** | **0.2** | **0.2~0.3** | 4.2~4.7 | 19.3~20.4 | 19.5~20.6 | 142~193 | 相机自身上限 ≈30fps |
| v1.32 热降频（Thermal 4） | 18.6~21.7 | 0.2 | 0.3 | 4.5~5.6 | 33~36 | 33~36 | 173~178 | skin 43°C，相机解码也掉到 20fps |
| 录制 2784×1392（同场景） | 13.5~20 | – | – | – | – | – | – | 与掉频后的相机帧率一致 |

结论：
- 前 20 秒**预览跑满相机上限（29~30fps）**，管线只用 20ms，还有 1/3 余量；
- 连续跑 1 分钟后掉到 19~21fps 是**整机热降频**，不是链路退化——`dumpsys thermalservice` 报 `Thermal Status: 4`、skin 43°C（HAL 阈值 43 即 severe），同一时刻 `enc 20→36ms`、`js raf 11→50ms`、相机解码帧率 29→20 全部同步下降，而 `grab/gpu/read` 全程不变（0.2 / 0.3 / 4.5）；
- 剩余可压的只有 `enc`（1536 JPEG q85 + base64，20~36ms）和页面侧 `js upload`（15~27ms），两者都与画质直接挂钩。

录制与拍照（同一次测试，已 ffprobe 校验）：

```
录制 written=249 = recFrames 249（三个窗口 70+108+71），一帧不丢
     文件 2784×1392 / 148 帧 / 7.93s / 22.7MB，avg 18.66fps（按真实时间戳实时播放）
     编码器 23Mbps / profile 8 / color-range 2
拍照 2784×1392 JPEG95 ≈ 870KB
```

录制之所以能零回读，是 `GlPreview` 每帧都 `eglMakeCurrent(recSurface)` 后把等距图直接画进编码器 Surface；`written == recFrames` 说明送进去的每一帧都被编码写盘。

对比 v1.30（2176 采集 / 1536 预览）`fps=10.4 grab=15.4 gpu=5.7 enc=20.2 frame=42.2`，**预览帧率 2.6 倍**，且录制分辨率从 2048 提到 2784。

踩坑记录（都已在代码里修掉）：

| 现象 | 原因 | 处理 |
|---|---|---|
| 所有着色器编译失败，`ERROR: 0:2 ';'` | 模板占位符替换成空串，留下 `texture2D(...).` 这种残句 | 改为整段替换 `__FETCH__`，并显式 `vec3(...)` 转换 |
| 录制只有 1 帧（`written=1 dropped=2`） | 每帧只在建面时 `eglMakeCurrent` 过一次，后续绘制仍落在屏幕表面；`eglSwapBuffers` 报 `EGL_BAD_SURFACE(12301)` | 每帧录制前都 `eglMakeCurrent(recSurface)` |
| 同一帧被反复渲染 | 空闲 50ms 超时也会进 `renderFrame` | 用 `cameraFrameReady` 只在新相机帧到达时渲染 |
| 录制 Surface 建面 | Adreno 上 RECORDABLE 配置的窗口表面行为异常 | 先用普通 EGL 配置，失败再退回 RECORDABLE |

剩余可优化项：

1. `enc`（1536 JPEG q85 + base64）仍是单帧最大开销（约 20ms），可降到 q80 / 或把预览降到 1280。
2. `read` 回读 4.7MB 需 4–8ms，可用 PBO 双缓冲。
3. 拍照时的 CPU `rotateEquirect`（防抖开启时）还在 Java 里跑。

