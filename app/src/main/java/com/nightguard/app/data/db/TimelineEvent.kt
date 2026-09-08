package com.nightguard.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class EventType {
    APP_FOREGROUND,
    INCOGNITO_DETECTED,
    SETTINGS_OR_PERMISSION_ACCESS,
    UNLOCK_SELFIE,
    USER_SWITCH,
    LOCATION_LOG,
    DEVICE_CONNECTION,
    VOICE_MEMO,
    TAMPER_ATTEMPT,
    MONITORING_STATE
}

@Entity(tableName = "timeline_events")
data class TimelineEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: EventType,
    val timestamp: Long,
    val packageName: String? = null,
    val appLabel: String? = null,
    val detail: String? = null,
    val photoPath: String? = null,
    val audioPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyMeters: Float? = null
)
