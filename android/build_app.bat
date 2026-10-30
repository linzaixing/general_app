@echo off
set "JAVA_HOME=D:\Program Files\Android\Android Studio\jbr"
cd /d F:\vibe_coding\pano_camera\android
call C:\Users\Administrator\.gradle\wrapper\dists\gradle-8.7-bin\af3un6e4ivqgjcdo5lfa5efog\gradle-8.7\bin\gradle.bat assembleDebug --offline > build.log 2>&1
echo EXIT=%ERRORLEVEL% >> build.log
