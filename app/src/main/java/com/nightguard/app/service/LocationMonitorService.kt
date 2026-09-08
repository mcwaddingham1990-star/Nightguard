package com.nightguard.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nightguard.app.MainActivity
import com.nightguard.app.R
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.util.MonitoringState
import com.nightguard.app.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Logs the device's location on a fixed interval using the plain framework
 * LocationManager (no Google Play Services dependency). Local-only, same as
 * the rest of the timeline: this never leaves the device.
 */
class LocationMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: TimelineRepository
    private lateinit var locationManager: LocationManager
    private var listener: LocationListener? = null

    override fun onCreate() {
        super.onCreate()
        repo = TimelineRepository(applicationContext)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startForeground(NOTIFICATION_ID, buildNotification())
        startUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        listener?.let { locationManager.removeUpdates(it) }
        super.onDestroy()
    }

    private fun startUpdates() {
        if (!PermissionUtils.hasLocationAccess(this)) return

        val newListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = logLocation(location, "Periodic location update")
            override fun onProviderDisabled(provider: String) {
                scope.launch { repo.log(type = EventType.SETTINGS_OR_PERMISSION_ACCESS, detail = "Location provider disabled: $provider") }
            }
        }
        listener = newListener

        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { locationManager.isProviderEnabled(it) },
            LocationManager.NETWORK_PROVIDER.takeIf { locationManager.isProviderEnabled(it) }
        )
        for (provider in providers) {
            try {
                locationManager.requestLocationUpdates(provider, MIN_UPDATE_INTERVAL_MS, MIN_UPDATE_DISTANCE_M, newListener)
                locationManager.getLastKnownLocation(provider)?.let { logLocation(it, "Last known location on start") }
            } catch (e: SecurityException) {
                // Permission was revoked between the check above and this call; the
                // watchdog in AppUsageMonitorService will notice and flag it.
            }
        }
    }

    private fun logLocation(location: Location, reason: String) {
        if (MonitoringState.isPaused(this)) return
        scope.launch {
            repo.log(
                type = EventType.LOCATION_LOG,
                detail = reason,
                latitude = location.latitude,
                longitude = location.longitude,
                locationAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                timestamp = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "nightguard_location"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "NightGuard location logging", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val openIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("NightGuard is active")
            .setContentText("Logging device location on this device")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1003
        private const val MIN_UPDATE_INTERVAL_MS = 5 * 60_000L
        private const val MIN_UPDATE_DISTANCE_M = 50f

        fun start(context: Context) {
            if (!PermissionUtils.hasLocationAccess(context)) return
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, LocationMonitorService::class.java))
        }
    }
}
