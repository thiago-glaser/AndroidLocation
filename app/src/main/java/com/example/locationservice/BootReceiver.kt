package com.example.locationservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        AppLogger.init(context)
        val action = intent?.action ?: return
        AppLogger.i("BootReceiver", "Received boot broadcast action: $action")

        val validBootActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (!validBootActions.contains(action)) {
            AppLogger.w("BootReceiver", "Ignoring non-boot action: $action")
            return
        }

        try {
            AppLogger.i("BootReceiver", "Starting LocationLoggingService after boot ($action)")
            LocationLoggingService.ensureServiceRunning(context)
            LocationLoggingService.scheduleWatchdogAlarm(context, 5000L)
        } catch (e: Exception) {
            AppLogger.e("BootReceiver", "Failed to start service after $action", e)
        }
    }
}
