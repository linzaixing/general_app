package com.robotx.app

import android.app.Application
import com.robotx.app.network.ApiConfig

class RobotXApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ApiConfig.init(this)
    }

    companion object {
        @Volatile
        lateinit var instance: RobotXApp
            private set
    }
}
