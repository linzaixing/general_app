# general_app

通用 Android 应用仓库。每个分支存放一个独立应用，互不依赖；分支之间没有共同代码，构建方式基本相同。

## 分支总览

| 分支 | 应用 | 说明 |
|---|---|---|
| `main` | — | 仓库总览与通用说明（当前页面） |
| `feature/art` | `smart_art` | 心情打卡、心情日历、伙伴图鉴与 AR 互动，数据仅存本机 |
| `feature/arm` | 见该分支 README | 机器人/智能体手机端控制台 |

> 应用的具体功能、构建与验证说明，请切换到对应分支查看该分支的 README。

## 通用约定

- **技术栈**：Kotlin + Jetpack Compose，Gradle Kotlin DSL，JDK 17。
- **构建**：进入对应分支后执行 `./gradlew :app:assembleDebug`；产物在 `app/build/outputs/apk/debug/`。
- **安装**：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
- **不提交**：`local.properties`、`.gradle/`、`build/`、`app/build/`、签名文件、任何密钥或 Token。

## 分支开发流程

```bash
git clone git@github.com:linzaixing/general_app.git
cd general_app
git switch feature/<name>     # 切换到目标应用分支

# 开发完成后
git add -A
git commit -m "说明本次改动"
git push
```

新建应用分支时，从空历史开始，避免把其他应用的代码带进来：

```bash
git switch --orphan feature/<name>
```

## 文档位置

- 原 `main` 分支的应用说明：`docs/fly_agent.md`
- 各分支的应用说明：对应分支根目录的 `README.md`
