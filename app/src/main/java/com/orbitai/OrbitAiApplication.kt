package com.orbitai

import android.app.Application
import com.orbitai.core.di.AppContainer
import com.orbitai.core.di.DefaultAppContainer
import com.orbitai.core.logging.FileLogger

private const val TAG = "OrbitAiApp"

class OrbitAiApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        // FileLogger.init() logs the startup context (version, device, session ID)
        FileLogger.init(this)
        FileLogger.i(TAG, "App init start")
        try {
            container = DefaultAppContainer(this)
            FileLogger.i(TAG, "App init success", "container=DefaultAppContainer")
        } catch (e: Exception) {
            FileLogger.e(TAG, "App init failed", e, "container=DefaultAppContainer")
            throw e
        }
    }
}
