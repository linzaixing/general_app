package com.robotx.app

import android.app.Application
import com.robotx.app.arm.ArmConfig
import com.robotx.app.arm.VoiceManager
import com.robotx.app.network.ApiConfig

class RobotXApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ApiConfig.init(this)
        ArmConfig.init(this)
        // TTS 初始化有延迟，必须在进程启动时就绑好，否则第一句报警会被丢掉。
        VoiceManager.init(this)
    }

    companion object {
        @Volatile
        lateinit var instance: RobotXApp
            private set
    }
}
