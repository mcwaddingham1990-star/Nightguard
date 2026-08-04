package com.nightguard.app.data

import android.content.Context
import com.nightguard.app.data.db.EventType
import com.nightguard.app.data.db.NightGuardDatabase
import com.nightguard.app.data.db.TimelineEvent
import kotlinx.coroutines.flow.Flow

class TimelineRepository(context: Context) {
    private val dao = NightGuardDatabase.getInstance(context).eventDao()

    fun observeEvents(): Flow<List<TimelineEvent>> = dao.observeAll()

    suspend fun log(
        type: EventType,
        packageName: String? = null,
        appLabel: String? = null,
        detail: String? = null,
        photoPath: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        dao.insert(
            TimelineEvent(
                type = type,
                timestamp = timestamp,
                packageName = packageName,
                appLabel = appLabel,
                detail = detail,
                photoPath = photoPath
            )
        )
    }

    suspend fun lastOfType(type: EventType) = dao.lastOfType(type)

    suspend fun pruneOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        dao.pruneOlderThan(cutoff)
    }
}
