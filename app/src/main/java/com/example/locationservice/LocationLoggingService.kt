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
import java.text.SimpleDateFormat
import java.util.*
import android.provider.Settings
import android.location.Location
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.google.android.gms.location.LocationServices


class LocationLoggingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_logging_channel"
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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        db = LocationDatabase.getDatabase(this)
        startLocationUpdates() // Now safe
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "onStartCommand: Starting foreground")

        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }

        startForeground(NOTIFICATION_ID, buildNotification()) // FIXED
        startLocationUpdates()
        sendQueuedLocations()

        return START_STICKY
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)  // ← INCREASED
            .setMaxUpdateDelayMillis(UPDATE_INTERVAL_MS * 2)
            .build()

        locationCallback = object : LocationCallback() {
            private var lastSentTime = 0L

            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val now = System.currentTimeMillis()

                // DEBOUNCE: Only process if 30+ seconds since last
                if (now - lastSentTime < 30_000) return

                Log.d("LocationService", "GPS: ${location.latitude}, ${location.longitude}, Alt: ${location.altitude}")
                queueLocation(location)
                lastSentTime = now
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
            Log.e("LocationService", "Permission denied", e)
            stopSelf()
        }
    }
    private fun queueLocation(location: Location) {
        scope.launch {
            val deviceId = getDeviceAndroidId()
            val utcTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(location.time))

            // DEDUPLICATE: Check if same coords + time already queued
            val existing = db.locationDao().getOldest()
            if (existing != null &&
                Math.abs(existing.latitude - location.latitude) < 0.00001 &&
                Math.abs(existing.longitude - location.longitude) < 0.00001 &&
                existing.timestampUtc == utcTime
            ) {
                Log.d("LocationService", "DUPLICATE SKIPPED: $utcTime")
                return@launch
            }

            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("altitude", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
                put("timestamp_utc", utcTime)
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
            Log.d("LocationService", "NEW LOCATION → Queued: $json")
            sendQueuedLocations()
        }
    }
    private var isSending = false // Add this flag

    private fun sendQueuedLocations() {
        if (isSending) return // Prevent multiple senders
        isSending = true

        scope.launch {
            while (true) {
                val location = db.locationDao().getOldest() ?: break

                val url = "http://thiagoglaser.ddns.net:50080/LocationData"
                Log.d("LocationService", "Attempting to send queued location ID: ${location.id}")
                Log.d("LocationService", "JSON Payload: ${location.jsonPayload}")

                val request = Request.Builder()
                    .url(url)
                    .post(location.jsonPayload.toRequestBody(JSON))
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        val code = response.code
                        val body = response.body?.string() ?: "null"
                        Log.d("LocationService", "HTTP Response Code: $code")
                        Log.d("LocationService", "Response Body: $body")

                        if (response.isSuccessful) {
                            db.locationDao().delete(location)
                            Log.d("LocationService", "SUCCESS: Sent & deleted location ID: ${location.id}")
                        } else {
                            Log.w("LocationService", "SERVER ERROR $code: Will retry later")
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LocationService", "NETWORK ERROR: Will retry later", e)
                    return@launch
                }
            }
            isSending = false // Reset when done
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

    override fun onDestroy() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Logger Active")
            .setContentText("Collecting GPS data every 60s")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation) // Must be valid
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent location logging"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
