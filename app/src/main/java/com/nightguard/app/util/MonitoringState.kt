package com.nightguard.app.util

import android.content.Context

/** Whether routine logging/capture is currently paused (via the PIN-gated pause screen). */
object MonitoringState {
    const val MAX_PAUSE_MS = 4 * 60 * 60_000L // 4 hours -- a pause always auto-resumes, it can't be left on indefinitely by accident.

    fun isPaused(context: Context): Boolean =
        SecurePrefs(context).pausedUntil() > System.currentTimeMillis()
}
