package com.nightguard.app.data

import android.content.Context
import com.nightguard.app.data.db.EventType
import com.nightguard.app.data.db.NightGuardDatabase
import com.nightguard.app.data.db.TimelineEvent
import com.nightguard.app.util.MonitoringState
import kotlinx.coroutines.flow.Flow

class TimelineRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = NightGuardDatabase.getInstance(context).eventDao()

    fun observeEvents(): Flow<List<TimelineEvent>> = dao.observeAll()

    suspend fun log(
        type: EventType,
        packageName: String? = null,
        appLabel: String? = null,
        detail: String? = null,
        photoPath: String? = null,
        audioPath: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        locationAccuracyMeters: Float? = null,
        isIncognito: Boolean = false,
        timestamp: Long = System.currentTimeMillis()
    ) {
        // A deliberate, PIN-gated pause suppresses routine logging -- but never the two
        // event types that exist specifically to catch someone trying to blind NightGuard.
        if (type != EventType.MONITORING_STATE && type != EventType.TAMPER_ATTEMPT &&
            MonitoringState.isPaused(appContext)
        ) {
            return
        }
        dao.insert(
            TimelineEvent(
                type = type,
                timestamp = timestamp,
                packageName = packageName,
                appLabel = appLabel,
                detail = detail,
                photoPath = photoPath,
                audioPath = audioPath,
                latitude = latitude,
                longitude = longitude,
                locationAccuracyMeters = locationAccuracyMeters,
                isIncognito = isIncognito
            )
        )
    }

    suspend fun lastOfType(type: EventType) = dao.lastOfType(type)

    suspend fun eventsBetween(startMillis: Long, endMillis: Long) = dao.eventsBetween(startMillis, endMillis)

    suspend fun eventsAfter(afterMillis: Long) = dao.eventsAfter(afterMillis)

    suspend fun pruneOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        dao.pruneOlderThan(cutoff)
    }
}
