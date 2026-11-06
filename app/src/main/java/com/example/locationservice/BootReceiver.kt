package com.example.locationservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("BootReceiver", "Received broadcast action: $action")

        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (!validActions.contains(action)) {
            if (action == Intent.ACTION_PACKAGE_REPLACED) {
                val data = intent.dataString ?: return
                if (!data.contains(context.packageName)) return
            } else {
                return
            }
        }

        val pendingResult = goAsync()
        try {
            Log.d("BootReceiver", "Starting LocationLoggingService and scheduling fast watchdog after $action")
            // Immediate start
            LocationLoggingService.ensureServiceRunning(context.applicationContext)
            // Staggered 2-second safety-net alarm to ensure resurrection even if package installer was finalizing
            LocationLoggingService.scheduleWatchdogAlarm(context.applicationContext, 2000L)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start service after $action", e)
        } finally {
            pendingResult.finish()
        }
    }
}
