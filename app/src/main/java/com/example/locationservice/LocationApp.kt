package com.example.locationservice

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocationApp : Application() {
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i("LocationApp", "Application onCreate (pid=${android.os.Process.myPid()})")

        appScope.launch {
            try {
                val db = LocationDatabase.getDatabase(this@LocationApp)
                val apiKey = db.settingDao().getSetting("api_key")?.value
                val authToken = db.settingDao().getSetting("auth_token")?.value
                val manuallyStopped = db.settingDao().getSetting("service_manually_stopped")?.value == "true"

                if ((!apiKey.isNullOrEmpty() || !authToken.isNullOrEmpty()) && !manuallyStopped) {
                    AppLogger.i("LocationApp", "User session active; ensuring LocationLoggingService is running.")
                    LocationLoggingService.ensureServiceRunning(this@LocationApp)
                    LocationLoggingService.scheduleWatchdogAlarm(this@LocationApp, 5000L)
                }
            } catch (e: Exception) {
                AppLogger.w("LocationApp", "Failed to auto-check service state in Application.onCreate", e)
            }
        }
    }
}

