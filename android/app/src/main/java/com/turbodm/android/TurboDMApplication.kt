package com.turbodm.android

import android.app.Application
import com.turbodm.android.engine.DownloadCoordinator
import com.turbodm.android.service.DownloadForegroundService

class TurboDMApplication : Application() {
    lateinit var coordinator: DownloadCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        DownloadForegroundService.createChannels(this)
        coordinator = DownloadCoordinator(this)
    }
}
