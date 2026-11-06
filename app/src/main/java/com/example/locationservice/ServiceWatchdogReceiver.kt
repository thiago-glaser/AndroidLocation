package com.example.locationservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ServiceWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        try {
            Log.d("ServiceWatchdog", "Watchdog triggered: ensuring LocationLoggingService is running")
            LocationLoggingService.ensureServiceRunning(context.applicationContext)
            LocationLoggingService.scheduleWatchdogAlarm(context.applicationContext)
        } catch (e: Exception) {
            Log.e("ServiceWatchdog", "Error in watchdog receiver", e)
        } finally {
            pendingResult.finish()
        }
    }
}

