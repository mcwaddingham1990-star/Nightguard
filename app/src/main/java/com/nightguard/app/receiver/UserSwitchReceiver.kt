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
 * Only fires if the device actually has multiple Android user/guest profiles
 * configured; a single-user phone will never see these broadcasts.
 */
class UserSwitchReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val detail = when (intent.action) {
            Intent.ACTION_USER_FOREGROUND -> "This user profile became active"
            Intent.ACTION_USER_BACKGROUND -> "This user profile moved to background (another profile took over)"
            else -> return
        }
        scope.launch {
            TimelineRepository(context.applicationContext).log(
                type = EventType.USER_SWITCH,
                detail = detail
            )
        }
    }
}
