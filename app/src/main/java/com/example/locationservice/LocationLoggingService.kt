package com.example.locationservice
import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import java.util.concurrent.TimeUnit

class LocationLoggingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_logging_channel"
        private const val SESSION_EVENT_CHANNEL_ID = "session_event_channel"
        private const val UPDATE_INTERVAL_MS = 7_500L
        private const val SEND_BATCH_INTERVAL_MS = 5 * 60 * 1000L
        private val SENT_DATA_RETENTION_DAYS = 15L
        private const val API_BASE_URL = "https://thiagoglaser.ddns.net/api"
        private const val API_KEY_SETTING = "api_key"

        fun getDeviceAndroidId(context: Context): String {
            return try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    .takeIf { it != "9774d56d682e549c" }
                    ?: "UNKNOWN_ID"
            } catch (e: Exception) {
                "UNKNOWN_ID"
            }
        }
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
        startLocationUpdates()
        createSessionEventNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "onStartCommand: Starting foreground")
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
        startPeriodicSending()
        return START_STICKY
    }

    private fun startPeriodicSending() {
        scope.coroutineContext.cancelChildren()
        scope.launch {
            while (isActive) {
                delay(SEND_BATCH_INTERVAL_MS)
                Log.d("LocationService", "Interval reached, sending batch and cleaning up old data.")
                sendQueuedLocations()
                sendQueuedSessionEvents()
                cleanupOldSentData()
                cleanupOldSessionEvents()
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
            fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            Log.d("LocationService", "Location updates requested")
        } catch (e: SecurityException) {
            Log.e("LocationService", "Permission denied", e)
            stopSelf()
        }
    }

    private fun queueLocation(location: Location) {
        scope.launch {
            val deviceId = getDeviceAndroidId(this@LocationLoggingService)
            val utcTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(location.time))

            val payload = QueuedLocation(
                deviceId = deviceId,
                timestampUtc = utcTime,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else 0.0
            )

            try {
                db.locationDao().insert(payload)
                Log.d("LocationService", "NEW LOCATION QUEUED: $utcTime | ${location.latitude}, ${location.longitude}")
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                Log.d("LocationService", "DUPLICATE IGNORED (same timestamp): $utcTime")
            } catch (e: Exception) {
                Log.e("LocationService", "Failed to queue location", e)
            }
        }
    }

    private var isSending = false

    private fun sendQueuedLocations() {
        if (isSending) return
        isSending = true

        val batchSize = 500

        scope.launch {
            try {
                val apiKey = db.settingDao().getSetting(API_KEY_SETTING)?.value
                if (apiKey.isNullOrEmpty()) {
                    Log.w("LocationService", "API key is not set. Cannot send location data.")
                    return@launch
                }

                val locations = db.locationDao().getUnsentBatch(batchSize)
                if (locations.isEmpty()) {
                    Log.d("LocationService", "Queue empty. Nothing to send.")
                    return@launch
                }

                val payloadObj = JSONObject().apply {
                    put("device_id", locations.first().deviceId)
                    val locArray = JSONArray()
                    for (loc in locations) {
                        locArray.put(JSONObject().apply {
                            put("latitude", loc.latitude)
                            put("longitude", loc.longitude)
                            put("altitude", loc.altitude)
                            put("timestamp_utc", loc.timestampUtc)
                        })
                    }
                    put("locations", locArray)
                }
                val batchPayload = payloadObj.toString()

                val url = "$API_BASE_URL/LocationData"
                val request = Request.Builder()
                    .url(url)
                    .header("X-API-Key", apiKey)
                    .post(batchPayload.toRequestBody(JSON))
                    .build()
                
                Log.d("LocationService", "Sending location data to $url. Method: ${request.method}. Payload: $batchPayload")

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    Log.d("LocationService", "HTTP Response Code: $code")

                    if (response.isSuccessful) {
                        val sentIds = locations.map { it.id }
                        db.locationDao().markAsSent(sentIds, System.currentTimeMillis())
                        Log.d("LocationService", "SUCCESS: Sent & marked batch of ${locations.size} locations.")
                    } else {
                        val errorBody = response.body?.string()
                        Log.w("LocationService", "SERVER ERROR $code: Will retry on next interval. Body: $errorBody")
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationService", "NETWORK/DB error – will retry later", e)
            } finally {
                isSending = false
            }
        }
    }

    private fun sendQueuedSessionEvents() {
        scope.launch {
            val apiKey = db.settingDao().getSetting(API_KEY_SETTING)?.value
            if (apiKey.isNullOrEmpty()) {
                Log.w("LocationService", "API key is not set. Cannot send session events.")
                return@launch
            }

            val events = db.sessionEventDao().getUnsentBatch(100)
            if (events.isEmpty()) {
                return@launch
            }

            for (event in events) {
                val endpoint = if (event.eventType == "start") "start-session" else "end-session"
                val json = "{\"device_id\":\"${event.deviceId}\",\"timestamp_utc\":\"${event.timestampUtc}\"}"
                val body = json.toRequestBody(JSON)
                val request = Request.Builder()
                    .url("$API_BASE_URL/Session/$endpoint")
                    .header("X-API-Key", apiKey)
                    .post(body)
                    .build()

                Log.d("LocationService", "Sending session event to ${request.url}. Method: ${request.method}. Payload: $json")

                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            db.sessionEventDao().markAsSent(listOf(event.id), System.currentTimeMillis())
                            Log.d("LocationService", "Session event '${event.eventType}' sent for device ${event.deviceId}")
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            val notification = NotificationCompat.Builder(this@LocationLoggingService, SESSION_EVENT_CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle("Session Event: ${event.eventType}")
                                .setContentText("Device: ${event.deviceId}")
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setAutoCancel(true)
                                .build()
                            notificationManager.notify(event.id.toInt(), notification)
                        } else {
                            val errorBody = response.body?.string()
                            Log.w("LocationService", "SERVER ERROR ${response.code} for session event: Will retry on next interval. Body: $errorBody")
                            // Stop processing the batch on failure to maintain order
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LocationService", "NETWORK/DB error for session event – will retry later", e)
                    // Stop processing the batch on failure to maintain order
                    return@launch
                }
            }
        }
    }

    private fun cleanupOldSentData() {
        scope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(SENT_DATA_RETENTION_DAYS)
            db.locationDao().deleteSentBefore(cutoff)
            Log.d("LocationService", "Cleaned up sent records older than 15 days.")
        }
    }

    private fun cleanupOldSessionEvents() {
        scope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(SENT_DATA_RETENTION_DAYS)
            db.sessionEventDao().deleteSentBefore(cutoff)
            Log.d("LocationService", "Cleaned up sent session events older than 15 days.")
        }
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Logger Active")
            .setContentText("Collecting GPS data every ~8 seconds")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Location Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent location logging"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createSessionEventNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Session Events"
            val descriptionText = "Notifications for session start and end events."
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(SESSION_EVENT_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}