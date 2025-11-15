package com.example.locationservice
import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.provider.Settings
import android.location.Location
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class LocationLoggingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_logging_channel"
        private const val FILE_NAME = "locations.txt"
        private const val UPDATE_INTERVAL_MS = 60_000L   // 1 minute
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private lateinit var db: LocationDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        db = LocationDatabase.getDatabase(this)
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "onStartCommand: Starting foreground")
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS / 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    Log.d("LocationService", "GPS: ${location.latitude}, ${location.longitude}, Alt: ${location.altitude}")
                    queueLocation(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.d("LocationService", "Location updates requested")
        } catch (e: SecurityException) {
            Log.e("LocationService", "Permission denied at runtime", e)
            stopSelf()
        }
    }

    private fun queueLocation(location: Location) {
        scope.launch {
            val deviceId = getDeviceId()
            val utcTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(location.time))

            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("timestamp_utc", utcTime)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("altitude", if (location.hasAltitude()) location.altitude else null)
            }.toString()

            val payload = QueuedLocation(
                deviceId = deviceId,
                timestampUtc = utcTime,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else 0.0,
                jsonPayload = json
            )

            db.locationDao().insert(payload)
            Log.d("LocationService", "Queued: $json")

            // Try to send immediately
            sendQueuedLocations()
        }
    }

    private fun sendQueuedLocations() {
        scope.launch {
            while (true) {
                val location = db.locationDao().getOldest() ?: break
                val request = Request.Builder()
                    .url("http://thiagoglaser.ddns.net:50080/PostLocationData") // PLACEHOLDER
                    .post(location.jsonPayload.toRequestBody(JSON))
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            db.locationDao().delete(location)
                            Log.d("LocationService", "Sent & deleted: ${location.id}")
                        } else {
                            Log.w("LocationService", "Server error: ${response.code}")
                            break // Wait for next retry
                        }
                    }
                } catch (e: IOException) {
                    Log.e("LocationService", "Network error, will retry later", e)
                    break // Wait for next location or retry
                }
            }
        }
    }

    private fun getDeviceAndroidId(): String {
        return try {
            // Best: Android ID (persistent, per-device)
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                .takeIf { it != "9774d56d682e549c" } // Filter out emulator default
                ?: "UNKNOWN_ID"
        } catch (e: Exception) {
            "UNKNOWN_ID"
        }
    }

    // ---------- Notification ----------
    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Logging")
            .setContentText("Collecting location every minute")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Logging Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows that the service is running and logging location"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}