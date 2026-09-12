package com.enmapatcher

import android.app.Application
import com.enmapatcher.patcher.CrashLogs

class EnmaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogs.install(this)
    }
}
