package com.nightguard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nightguard.app.service.AppUsageMonitorService
import com.nightguard.app.service.LocationMonitorService
import com.nightguard.app.util.PermissionUtils

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (PermissionUtils.hasUsageAccess(context)) {
            ContextCompat.startForegroundService(context, Intent(context, AppUsageMonitorService::class.java))
        }
        LocationMonitorService.start(context)
    }
}
