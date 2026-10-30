Set sh = CreateObject("WScript.Shell")
sh.Environment("PROCESS")("JAVA_HOME") = "D:\Program Files\Android\Android Studio\jbr"
sh.Run "cmd /c ""cd /d F:\vibe_coding\pano_camera\android && call C:\Users\Administrator\.gradle\wrapper\dists\gradle-8.7-bin\af3un6e4ivqgjcdo5lfa5efog\gradle-8.7\bin\gradle.bat assembleDebug > build.log 2>&1""", 0, False
