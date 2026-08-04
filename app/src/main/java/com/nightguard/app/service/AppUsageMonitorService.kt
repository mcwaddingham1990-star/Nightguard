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
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Polls UsageStatsManager for ACTIVITY_RESUMED events and logs each foreground-app
 * switch to the local timeline. Requires the user to have granted Usage Access.
 */
class AppUsageMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private lateinit var repo: TimelineRepository
    private var lastQueryEnd = 0L
    private var lastForegroundPackage: String? = null

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
            delay(POLL_INTERVAL_MS)
        }
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
    }
}
