package com.nightguard.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.nightguard.app.service.AppUsageMonitorService
import com.nightguard.app.ui.NightGuardNavHost
import com.nightguard.app.ui.theme.NightGuardTheme
import com.nightguard.app.util.PermissionUtils

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NightGuardTheme {
                NightGuardNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (PermissionUtils.hasUsageAccess(this)) {
            ContextCompat.startForegroundService(this, Intent(this, AppUsageMonitorService::class.java))
        }
    }
}
