package com.nightguard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Logs unlock times without a selfie -- photo capture on a plain unlock was removed on
 * request (it was firing every single unlock, not just the events worth a selfie). The
 * incognito, sensitive-settings, and tamper-watchdog capture triggers are unaffected;
 * this only covers the "device was unlocked" case.
 */
class UnlockReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        scope.launch {
            TimelineRepository(context.applicationContext).log(
                type = EventType.UNLOCK_SELFIE,
                detail = "Device unlocked"
            )
        }
    }
}
