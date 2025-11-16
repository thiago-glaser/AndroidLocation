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
import org.json.JSONArray


class LocationLoggingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_logging_channel"
        private const val UPDATE_INTERVAL_MS = 15_000L   // ← CHANGED: 15 seconds
        private const val SEND_BATCH_INTERVAL_MS = 5 * 60 * 1000L // NEW: 5 minutes
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

        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
        // CHANGED: Start periodic sending instead of immediate one-off
        startPeriodicSending()

        return START_STICKY
    }

    private fun startPeriodicSending() {
        // Cancel existing job if present
        scope.coroutineContext.cancelChildren()

        scope.launch {
            while(isActive) {
                // Wait for the 5-minute interval
                delay(SEND_BATCH_INTERVAL_MS)
                Log.d("LocationService", "5-minute interval reached, attempting to send batch.")
                sendQueuedLocations()
            }
        }
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(UPDATE_INTERVAL_MS * 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                Log.d("LocationService", "GPS: ${location.latitude}, ${location.longitude}, Alt: ${location.altitude}")
                queueLocation(location)
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

            val payload = QueuedLocation(
                deviceId = deviceId,
                timestampUtc = utcTime,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else 0.0,
                jsonPayload = ""   // not used for sending any more
            )

            try {
                db.locationDao().insert(payload)
                Log.d("LocationService", "NEW LOCATION QUEUED: $utcTime | ${location.latitude}, ${location.longitude}")
                // Try to send immediately (still respects 5-min batching)
                sendQueuedLocations()
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Duplicate timestamp_utc → ignore silently (guaranteed no dup)
                Log.d("LocationService", "DUPLICATE IGNORED (same timestamp): $utcTime")
            } catch (e: Exception) {
                Log.e("LocationService", "Failed to queue location", e)
            }
        }
    }
    private var isSending = false // Add this flag

    private fun sendQueuedLocations() {
        if (isSending) return
        isSending = true

        val batchSize = 100

        scope.launch {
            try {
                // -------------------------------------------------
                // 1. Get a batch of queued locations
                // -------------------------------------------------
                val locations = db.locationDao().getBatch(batchSize)
                if (locations.isEmpty()) {
                    Log.d("LocationService", "Queue empty. Nothing to send.")
                    return@launch
                }

                // -------------------------------------------------
                // 2. Build the **new** payload format
                // -------------------------------------------------
                val payloadObj = JSONObject().apply {
                    put("device_id", locations.first().deviceId)   // same for whole batch
                    val locArray = JSONArray()
                    for (loc in locations) {
                        locArray.put(JSONObject().apply {
                            put("latitude", loc.latitude)
                            put("longitude", loc.longitude)
                            put("altitude", if (loc.altitude != 0.0) loc.altitude else JSONObject.NULL)
                            put("timestamp_utc", loc.timestampUtc)
                        })
                    }
                    put("locations", locArray)
                }
                val batchPayload = payloadObj.toString()

                // -------------------------------------------------
                // 3. LOG the exact JSON that will be sent
                // -------------------------------------------------
                val pretty = try { JSONObject(batchPayload).toString(2) } catch (e: Exception) { batchPayload }
                Log.d("LocationService", "Sending JSON batch (${locations.size} items):\n$pretty")

                // -------------------------------------------------
                // 4. POST to the server
                // -------------------------------------------------
                val url = "http://thiagoglaser.ddns.net:50080/LocationData"
                val request = Request.Builder()
                    .url(url)
                    .post(batchPayload.toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    Log.d("LocationService", "HTTP Response Code: $code")

                    if (response.isSuccessful) {
                        // Delete the whole batch only on success
                        locations.forEach { db.locationDao().delete(it) }
                        Log.d("LocationService",
                            "SUCCESS: Sent & deleted batch of ${locations.size} locations.")
                    } else {
                        Log.w("LocationService",
                            "SERVER ERROR $code: Will retry on next interval.")
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationService",
                    "NETWORK/DB error – will retry later", e)
            } finally {
                isSending = false
            }
        }
    }    private fun getDeviceAndroidId(): String {
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
        // ADD: Cancel the coroutine scope when the service is destroyed
        scope.cancel()
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
