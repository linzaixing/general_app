#requires -Version 5.1
<#
.SYNOPSIS
    一键构建 Robot X Android Debug APK。
.DESCRIPTION
    自动设置 JAVA_HOME / ANDROID_HOME 后调用 gradlew assembleDebug。
    第一次执行已经下载好 JDK 17 与 Gradle 8.7，再次运行直接走缓存。
.PARAMETER Install
    构建成功后通过 adb 将 APK 推到第一台已连接的设备并启动 MainActivity。
.EXAMPLE
    .\build.ps1
    .\build.ps1 -Install
#>

param(
    [switch]$Install,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

$env:JAVA_HOME = "D:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;" + $env:Path

if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw "JAVA_HOME ($env:JAVA_HOME) is invalid. Run the bootstrap once or edit build.ps1."
}
if (-not (Test-Path "$env:ANDROID_HOME\platforms\android-34")) {
    throw "Android SDK platform-34 missing under $env:ANDROID_HOME"
}

Write-Host "JAVA_HOME    = $env:JAVA_HOME"
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"

# Ensure local.properties exists for AGP
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($env:ANDROID_HOME -replace '\\','\\')" | Out-File -Encoding ASCII local.properties
}

if ($Clean) {
    & .\gradlew.bat clean
}

& .\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }
$size = "{0:N2} MB" -f ((Get-Item $apk).Length / 1MB)
Write-Host ""
Write-Host "BUILD OK -> $apk ($size)"

# 产物归档到 dist\：统一命名 deeparm_console_v<版本>.apk。
# v1.1.0 起应用显示名从「机械臂小助手」改为 deeparm_console，APK 命名同步更名并保持一致。
# ⚠️ 与 app/build.gradle.kts 的 versionName 同步改（v1.2.1：URDF 四零件支架网格 + 轴向居中）。
$version = "1.2.1"
$distDir = Join-Path $projectRoot "dist"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$distApk = Join-Path $distDir "deeparm_console_v$version.apk"
Copy-Item $apk $distApk -Force
Write-Host "DIST OK  -> $distApk"

if ($Install) {
    & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r $apk
    if ($LASTEXITCODE -eq 0) {
        & "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n "com.robotx.app/.MainActivity"
    }
}
