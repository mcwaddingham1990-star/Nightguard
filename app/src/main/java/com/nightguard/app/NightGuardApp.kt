package com.nightguard.app

import android.app.Application
import com.nightguard.app.work.BackupWorker

class NightGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BackupWorker.schedule(this)
    }
}
