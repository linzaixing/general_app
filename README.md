# fly_agent

一个原生 Android 应用，作为机器人/智能体的手机端控制台：

- **按住说话**：按住麦克风按钮录音，松手后上传到云端 ASR，识别成文本
- **拍照 / 录像**：进入相机页拍照（点击）或录像（长按），返回主界面输入指令后发送
- **VLA 多模态推理**：把图片 / 视频帧 + 你的文字 prompt 一起发送给云端 Vision-Language-Action 大模型，获得描述与动作建议
- **可配置的 endpoint**：默认对接阿里云 DashScope 的 OpenAI 兼容协议，也可一键切换到 OpenAI / 自建后端

## 新电脑 5 分钟跑通

### 1) 仓库里必须有这些文件

- `gradlew`、`gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar`
- `settings.gradle.kts`、`build.gradle.kts`、`app/build.gradle.kts`、`gradle.properties`
- `build.ps1`、`README.md`、`app/src/main/**`

### 2) 不要提交这些文件

- `local.properties`
- `.gradle/`、`build/`、`app/build/`
- 本机 JDK/SDK 目录
- 任何 API Key、token、私钥

### 3) 新电脑最短命令

```powershell
git clone <your-repo-url>
cd robot_x
# 设置 JAVA_HOME 与 ANDROID_HOME 后
./build.ps1
```

连接手机后可直接：

```powershell
./build.ps1 -Install
```

## 目录结构

```text
robot_x/
├── settings.gradle.kts
├── build.gradle.kts                root project
├── gradle.properties
├── gradle/wrapper/                 wrapper config (jar 需自行生成，见下方)
├── gradlew / gradlew.bat
└── app/
    ├── build.gradle.kts            模块依赖（Compose、CameraX、OkHttp …）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/robotx/app/
        │   ├── RobotXApp.kt
        │   ├── MainActivity.kt
        │   ├── ui/                 Compose 界面（HomeScreen / CameraScreen / ChatBubble / SettingsDialog / theme）
        │   ├── audio/              录音 + ASR
        │   ├── camera/             CameraX 封装
        │   ├── network/            ApiConfig / OkHttp / VLA 客户端 / DTO
        │   ├── data/               ChatMessage / ChatRepository
        │   ├── permission/         运行时权限
        │   └── viewmodel/          HomeViewModel
        └── res/
```

## 一、环境准备

| 工具 | 版本 |
| --- | --- |
| JDK | **17**（必须，AGP 8.5 要求） |
| Android Studio | Hedgehog / Iguana / Koala 任意 |
| Android SDK Platform | 34 |
| Android Build-Tools | 34.0.0 |
| Gradle | 8.7（wrapper 自动） |
| Kotlin | 2.0.20（脚本声明） |

设置环境变量：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

## 二、Gradle Wrapper 说明

本仓库已包含完整 wrapper（含 `gradle-wrapper.jar`），新电脑无需手工再生成。

## 三、构建并安装到手机

最快的方式是用仓库自带的一键脚本：

```powershell
# 仅构建 (输出 app/build/outputs/apk/debug/app-debug.apk)
./build.ps1

# 构建并通过 adb 安装到第一台已连接设备，并启动 MainActivity
./build.ps1 -Install

# 清理 + 重新构建
./build.ps1 -Clean
```

`build.ps1` 会自动设置 `JAVA_HOME` / `ANDROID_HOME`（默认指向 `F:\vibe_coding\.tools\jdk17` 与 `%LOCALAPPDATA%\Android\Sdk`，按需修改脚本头部即可）。

如果你已经有自己的 JDK 17 + Android SDK，也可以直接：

```powershell
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接在 Android Studio 里点击 **Run**。

## 四、公网服务器网关（推荐）

如果你希望 App 统一连你自己的公网 IP（例如 `211.101.239.208`），可使用仓库内的网关模板：

- 目录：`server-gateway/`
- 接口：`/v1/audio/transcriptions`、`/v1/chat/completions`
- 作用：把 App 请求转发到阿里云/OpenAI/其他上游

服务器上执行：

```bash
cd server-gateway
cp .env.example .env
# 编辑 .env，填写 GATEWAY_API_KEY / UPSTREAM_API_KEY
docker compose up -d --build
```

App 设置填写：

- Base URL: `http://211.101.239.208:8000/v1`
- API Key: `.env` 中的 `GATEWAY_API_KEY`
- ASR 路径: `/audio/transcriptions`
- VLA 路径: `/chat/completions`

详细说明见 `server-gateway/README.md`。

## 五、首次启动配置

第一次启动 App 会自动弹出 **设置** 弹窗，要求你填写：

| 字段 | 默认值（DashScope） | 说明 |
| --- | --- | --- |
| Base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI 兼容根路径 |
| API Key | 留空 | 阿里云百炼控制台获取 |
| ASR 路径 | `/chat/completions` | DashScope 语音识别走兼容 chat 接口 |
| ASR 模型 | `qwen3-asr-flash` | DashScope 语音识别模型 |
| VLA 路径 | `/chat/completions` | OpenAI 兼容 |
| VLA 模型 | `qwen2.5-vl-7b-instruct` | 通义千问视觉版本 |

> 顶部右上角的齿轮按钮可随时回到设置页。

## 六、对接其它服务的填法

### 1. OpenAI

| 字段 | 值 |
| --- | --- |
| Base URL | `https://api.openai.com/v1` |
| ASR 路径 | `/audio/transcriptions` |
| ASR 模型 | `whisper-1`（或 `gpt-4o-mini-transcribe`） |
| VLA 路径 | `/chat/completions` |
| VLA 模型 | `gpt-4o` 或 `gpt-4o-mini` |

### 2. 火山引擎豆包 Ark（OpenAI 兼容）

| 字段 | 值 |
| --- | --- |
| Base URL | `https://ark.cn-beijing.volces.com/api/v3` |
| VLA 路径 | `/chat/completions` |
| VLA 模型 | 你的 endpoint id，例如 `ep-2026xxxx-xxxx` |

### 3. 自建后端

只要你的服务器实现了（任选一种 ASR 协议）：

```http
POST {base}/asr     (multipart: file, model)        -> { "text": "…" }
POST {base}/chat/completions (input_audio 格式)      -> { choices: [{ message: { content: … } }] }
POST {base}/vla/chat/completions  (OpenAI 风格)     -> { choices: [{ message: { content: … } }] }
```

并接受 `Authorization: Bearer <key>`，把 `Base URL` 指过去即可。

## 七、协议细节

### ASR 请求（自动按服务商切换）

#### OpenAI / 自建 multipart 模式

```http
POST {baseUrl}{asrPath}
Authorization: Bearer <apiKey>
Content-Type: multipart/form-data

model = <asrModel>
file  = <wav, 16kHz, 16bit, mono>
```

#### DashScope 兼容模式（qwen3-asr-flash）

```http
POST {baseUrl}/chat/completions
Authorization: Bearer <apiKey>
Content-Type: application/json

{
  "model": "qwen3-asr-flash",
  "messages": [{
    "role": "user",
    "content": [{
      "type": "input_audio",
      "input_audio": { "data": "data:audio/wav;base64,..." }
    }]
  }],
  "asr_options": { "language": "zh", "enable_itn": true },
  "stream": false
}
```

应用端会根据 `Base URL / ASR 模型 / ASR 路径` 自动识别 DashScope 场景并走上面协议。

### VLA 请求

```http
POST {baseUrl}{vlaPath}
Authorization: Bearer <apiKey>
Content-Type: application/json

{
  "model": "<vlaModel>",
  "messages": [
    { "role": "system", "content": "You are Robot X …" },
    { "role": "user",   "content": [
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,…" } },
        { "type": "text",      "text": "<用户 prompt>" }
    ]}
  ]
}
```

视频会被 App 端抽 4 帧 JPEG 注入 `image_url`，避免上传整个 MP4。

## 八、权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 调用云端 API |
| `CAMERA` | 拍照 / 录像 |
| `RECORD_AUDIO` | 按住说话录音 |

App 启动时会请求一次性运行时授权，被拒绝后再次触发对应功能时会再次请求。

## 九、路线图

- [ ] SSE 流式输出，用户可看到大模型逐字回复
- [ ] 多轮上下文记忆与持久化（Room）
- [ ] WebSocket 实时双工 ASR（边录边识别）
- [ ] 把 VLA 返回的 `action` JSON 转发给机器人执行端（MQTT / WebSocket）
- [ ] iOS 版本（Compose Multiplatform）
