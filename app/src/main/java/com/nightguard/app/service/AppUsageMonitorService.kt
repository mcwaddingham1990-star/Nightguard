package com.nightguard.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nightguard.app.MainActivity
import com.nightguard.app.R
import com.nightguard.app.capture.UnlockCaptureService
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Polls UsageStatsManager for ACTIVITY_RESUMED events and logs each foreground-app
 * switch to the local timeline. Also acts as NightGuard's watchdog: since this
 * service is independent of the Accessibility Service, it can notice (and
 * selfie-flag) someone turning that service off or revoking Usage Access —
 * the two most direct ways to blind NightGuard from itself.
 */
class AppUsageMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private lateinit var repo: TimelineRepository
    private var lastQueryEnd = 0L
    private var lastForegroundPackage: String? = null
    private var lastWatchdogCheck = 0L
    private var usageAccessWasGranted: Boolean? = null
    private var accessibilityWasEnabled: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        repo = TimelineRepository(applicationContext)
        lastQueryEnd = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        startForeground(NOTIFICATION_ID, buildNotification())
        loopJob = scope.launch { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        while (true) {
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(lastQueryEnd, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                    event.packageName != lastForegroundPackage
                ) {
                    lastForegroundPackage = event.packageName
                    logAppForeground(event.packageName, event.timeStamp)
                }
            }
            lastQueryEnd = now
            checkWatchdog(now)
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun checkWatchdog(now: Long) {
        if (now - lastWatchdogCheck < WATCHDOG_INTERVAL_MS) return
        lastWatchdogCheck = now

        val usageAccessGranted = PermissionUtils.hasUsageAccess(this)
        if (usageAccessWasGranted == true && !usageAccessGranted) {
            flagProtectionChange("Usage access permission was turned off for NightGuard")
        }
        usageAccessWasGranted = usageAccessGranted

        val accessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(this)
        if (accessibilityWasEnabled == true && !accessibilityEnabled) {
            flagProtectionChange("NightGuard's accessibility service was turned off")
        }
        accessibilityWasEnabled = accessibilityEnabled
    }

    private suspend fun flagProtectionChange(reason: String) {
        repo.log(type = EventType.SETTINGS_OR_PERMISSION_ACCESS, detail = reason)
        UnlockCaptureService.start(this, reason)
    }

    private suspend fun logAppForeground(packageName: String, timestamp: Long) {
        val label = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
        repo.log(
            type = EventType.APP_FOREGROUND,
            packageName = packageName,
            appLabel = label,
            timestamp = timestamp
        )

        if (packageName in SENSITIVE_APP_PACKAGES) {
            UnlockCaptureService.start(this, "Opened $label")
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "nightguard_monitor"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "NightGuard monitoring", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("NightGuard is active")
            .setContentText("Logging app usage on this device")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 15_000L
        private const val INITIAL_LOOKBACK_MS = 60_000L
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        // Opening these apps is itself a hiding mechanism, not just ordinary usage,
        // so it gets a selfie on top of the normal app-foreground log entry.
        private val SENSITIVE_APP_PACKAGES = setOf(
            "com.samsung.knox.securefolder" // Samsung Secure Folder
        )
    }
}
