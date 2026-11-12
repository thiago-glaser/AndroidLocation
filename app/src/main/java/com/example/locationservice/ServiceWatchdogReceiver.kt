package com.example.locationservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServiceWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        AppLogger.init(context)
        try {
            AppLogger.i("ServiceWatchdog", "Watchdog triggered: ensuring LocationLoggingService is running")
            LocationLoggingService.ensureServiceRunning(context)
            LocationLoggingService.scheduleWatchdogAlarm(context)
        } catch (e: Exception) {
            AppLogger.e("ServiceWatchdog", "Error in watchdog receiver", e)
        }
    }
}
