package com.nightguard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nightguard.app.capture.UnlockCaptureService

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            UnlockCaptureService.start(context, "Device unlocked")
        }
    }
}
