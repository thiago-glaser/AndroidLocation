package com.example.locationservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class ServiceWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        AppLogger.init(context)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = LocationDatabase.getDatabase(context)
                val manuallyStopped = db.settingDao().getSetting("service_manually_stopped")?.value == "true"
                if (manuallyStopped) {
                    AppLogger.d("ServiceWatchdog", "Service manually stopped; skipping watchdog restart.")
                    return@launch
                }

                val mode = db.settingDao().getSetting("tracking_mode")?.value?.uppercase(Locale.US) ?: "SESSION_ONLY"
                val latestEvent = db.sessionEventDao().getLatestEvent()
                val inSession = latestEvent?.eventType == "start"

                if (mode == "ALL_TIME" || inSession) {
                    AppLogger.i("ServiceWatchdog", "Watchdog triggered: active tracking required (mode=$mode, inSession=$inSession). Ensuring service running.")
                    LocationLoggingService.ensureServiceRunning(context)
                } else {
                    AppLogger.d("ServiceWatchdog", "Watchdog triggered: idle in SESSION_ONLY mode. Service not required.")
                }
                LocationLoggingService.scheduleWatchdogAlarm(context)
            } catch (e: Exception) {
                AppLogger.e("ServiceWatchdog", "Error in watchdog receiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
