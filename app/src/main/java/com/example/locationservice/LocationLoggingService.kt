package com.example.locationservice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class LiveGpsTelemetry(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val altitudeMeters: Double,
    val timestampMs: Long
)

class LocationLoggingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_logging_channel"
        private const val SESSION_EVENT_CHANNEL_ID = "session_event_channel"
        private const val UPDATE_INTERVAL_MS = 7_500L
        private const val SEND_BATCH_INTERVAL_MS = 5 * 60 * 1000L
        private const val SEND_BATCH_INTERVAL_ACTIVE_MS = 30 * 1000L
        private const val BLUETOOTH_REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L // 12 hours
        private val SENT_DATA_RETENTION_DAYS = 60L
        private const val API_BASE_URL = "https://travel-access.ddns.net/api"
        private const val API_KEY_SETTING = "api_key"

        private val _liveTelemetry = MutableStateFlow<LiveGpsTelemetry?>(null)
        val liveTelemetry: StateFlow<LiveGpsTelemetry?> = _liveTelemetry.asStateFlow()

        fun getDeviceAndroidId(context: Context): String {
            return try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    .takeIf { it != "9774d56d682e549c" }
                    ?: "UNKNOWN_ID"
            } catch (e: Exception) {
                "UNKNOWN_ID"
            }
        }

        fun ensureServiceRunning(context: Context) {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                Log.w("LocationService", "Cannot start LocationLoggingService: location permission not granted.")
                return
            }

            try {
                val serviceIntent = Intent(context, LocationLoggingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.w("LocationService", "Could not start LocationLoggingService", e)
            }
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var lastLocationFix: Location? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private lateinit var db: LocationDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var periodicJob: Job? = null
    private var lastBluetoothFetchTime = 0L

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        db = LocationDatabase.getDatabase(this)
        createSessionEventNotificationChannel()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LocationService::LocationWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
                Log.d("LocationService", "Partial WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.w("LocationService", "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("LocationService", "Partial WakeLock released")
            }
        } catch (e: Exception) {
            Log.w("LocationService", "Failed to release WakeLock", e)
        }
    }

    private suspend fun fetchBluetoothDevices() {
        val apiKey = db.settingDao().getSetting(API_KEY_SETTING)?.value
        if (apiKey.isNullOrEmpty()) {
            Log.w("LocationService", "API key is not set. Cannot fetch bluetooth devices.")
            return
        }

        val request = Request.Builder()
            .url("$API_BASE_URL/user/bluetooth")
            .header("X-API-Key", apiKey)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d("LocationService", "Bluetooth API Response: $responseBody")
                    if (responseBody != null) {
                        try {
                            val root = JSONObject(responseBody)
                            val jsonArray = root.getJSONArray("bluetooth")
                            val devices = mutableListOf<com.example.locationservice.BluetoothDevice>()
                            for (i in 0 until jsonArray.length()) {
                                val jsonObject = jsonArray.getJSONObject(i)
                                val device = com.example.locationservice.BluetoothDevice(
                                    name = jsonObject.optString("NAME", "Unknown Device"),
                                    description = jsonObject.optString("DESCRIPTION", ""),
                                    address = jsonObject.optString("ADDRESS", ""),
                                    carId = jsonObject.optString("CAR_ID", "")
                                )
                                devices.add(device)
                            }
                            db.bluetoothDeviceDao().deleteAll()
                            db.bluetoothDeviceDao().insertAll(devices)
                            lastBluetoothFetchTime = System.currentTimeMillis()
                            Log.d("LocationService", "Successfully fetched and saved ${devices.size} bluetooth devices.")
                        } catch (e: JSONException) {
                            Log.e("LocationService", "Failed to parse bluetooth devices response", e)
                        }
                    }
                } else {
                    val errorBody = response.body?.string()
                    Log.w("LocationService", "Failed to fetch bluetooth devices: ${response.code} at ${request.url}. Body: $errorBody")
                }
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Failed to fetch bluetooth devices", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LocationService", "onStartCommand: Starting foreground")
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        startLocationUpdates()
        startPeriodicSending()
        return START_STICKY
    }

    private fun startPeriodicSending() {
        if (periodicJob?.isActive == true) {
            // Already running periodic loop; flush any pending session events without resetting timer
            sendQueuedSessionEvents()
            return
        }

        periodicJob = scope.launch {
            // Initial fetch & flush
            fetchBluetoothDevices()
            sendQueuedSessionEvents()
            sendQueuedLocations()
            sendQueuedBluetoothDevices()

            var timeSinceLastSend = 0L
            val pollInterval = 5000L // check every 5 seconds

            while (isActive) {
                delay(pollInterval)
                timeSinceLastSend += pollInterval

                val latestEvent = db.sessionEventDao().getLatestEvent()
                val isSessionActive = latestEvent?.eventType == "start"
                val limit = if (isSessionActive) SEND_BATCH_INTERVAL_ACTIVE_MS else SEND_BATCH_INTERVAL_MS

                if (timeSinceLastSend >= limit) {
                    timeSinceLastSend = 0L
                    Log.d("LocationService", "Interval reached, sending batch and cleaning up old data.")

                    // Refresh bluetooth devices if interval exceeded
                    if (System.currentTimeMillis() - lastBluetoothFetchTime > BLUETOOTH_REFRESH_INTERVAL_MS) {
                        fetchBluetoothDevices()
                    }

                    sendQueuedLocations()
                    sendQueuedSessionEvents()
                    sendQueuedBluetoothDevices()
                    cleanupOldSentData()
                    cleanupOldSessionEvents()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (locationCallback != null) {
            Log.d("LocationService", "Location updates already registered")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(UPDATE_INTERVAL_MS * 2)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val speedKmh = computeSpeedKmh(location)
                val altMeters = if (location.hasAltitude() && location.altitude != 0.0) {
                    location.altitude
                } else {
                    _liveTelemetry.value?.altitudeMeters ?: 0.0
                }

                lastLocationFix = location
                _liveTelemetry.value = LiveGpsTelemetry(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedKmh = speedKmh,
                    altitudeMeters = altMeters,
                    timestampMs = location.time
                )

                Log.d(
                    "LocationService",
                    String.format(
                        Locale.US,
                        "GPS: %.7f, %.7f, Alt: %.1fm, Speed: %.1f km/h",
                        location.latitude,
                        location.longitude,
                        altMeters,
                        speedKmh
                    )
                )
                queueLocation(location)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            locationCallback = callback
            Log.d("LocationService", "Location updates requested")
        } catch (e: SecurityException) {
            Log.e("LocationService", "Permission denied", e)
            stopSelf()
        }
    }

    private fun computeSpeedKmh(location: Location): Double {
        if (location.hasSpeed() && location.speed > 0f) {
            val s = (location.speed * 3.6).toDouble()
            return if (s < 1.5) 0.0 else s
        }
        val prev = lastLocationFix ?: return 0.0
        val deltaMs = location.time - prev.time
        if (deltaMs !in 1_000L..60_000L) return 0.0

        val results = FloatArray(1)
        Location.distanceBetween(prev.latitude, prev.longitude, location.latitude, location.longitude, results)
        val distMeters = results[0].toDouble()
        if (distMeters < 5.0) return 0.0

        val computedKmh = (distMeters / (deltaMs / 1000.0)) * 3.6
        return if (computedKmh in 1.5..250.0) computedKmh else 0.0
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
            var url = ""
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

                url = "$API_BASE_URL/LocationData"
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
                        Log.w("LocationService", "SERVER ERROR $code at $url: Will retry on next interval. Body: $errorBody")
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationService", "NETWORK/DB error at $url – will retry later", e)
            } finally {
                isSending = false
            }
        }
    }

    private fun sendQueuedSessionEvents() {
        scope.launch {
            var url = ""
            try {
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
                    val endpoint = when (event.eventType) {
                        "start" -> "start-session"
                        "end" -> "end-session"
                        else -> "start-session" // Fallback to avoid /event
                    }
                    val json = JSONObject().apply {
                        put("device_id", event.deviceId)
                        put("bluetooth_address", event.bluetoothAddress)
                        put("timestamp_utc", event.timestampUtc)
                    }.toString()
                    val body = json.toRequestBody(JSON)
                    url = "$API_BASE_URL/Session/$endpoint"
                    val request = Request.Builder()
                        .url(url)
                        .header("X-API-Key", apiKey)
                        .post(body)
                        .build()

                    Log.d("LocationService", "Sending bluetooth event to ${request.url}. Payload: $json")

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            db.sessionEventDao().markAsSent(listOf(event.id), System.currentTimeMillis())
                            Log.d("LocationService", "Bluetooth event sent for address ${event.bluetoothAddress}")
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            val notification = NotificationCompat.Builder(this@LocationLoggingService, SESSION_EVENT_CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle("Bluetooth Event Queued")
                                .setContentText("Address: ${event.bluetoothAddress}")
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setAutoCancel(true)
                                .build()
                            notificationManager.notify(event.id.toInt(), notification)
                        } else {
                            val errorBody = response.body?.string()
                            Log.w("LocationService", "SERVER ERROR ${response.code} for session event at $url: Will retry on next interval. Body: $errorBody")
                            // Stop processing the batch on failure to maintain order
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationService", "NETWORK/DB error for session event at $url – will retry later", e)
                // Stop processing the batch on failure to maintain order
                return@launch
            }
        }
    }

    private fun sendQueuedBluetoothDevices() {
        scope.launch {
            var url = ""
            try {
                val apiKey = db.settingDao().getSetting(API_KEY_SETTING)?.value
                if (apiKey.isNullOrEmpty()) {
                    Log.w("LocationService", "API key is not set. Cannot send new bluetooth devices.")
                    return@launch
                }

                val devices = db.queuedBluetoothDeviceDao().getUnsent()
                if (devices.isEmpty()) {
                    return@launch
                }

                for (device in devices) {
                    val json = JSONObject()
                        .put("name", device.name)
                        .put("address", device.address)
                        .put("description", device.description)
                        .put("carId", device.carId)
                        .toString()

                    val body = json.toRequestBody(JSON)
                    url = "$API_BASE_URL/user/bluetooth"
                    val request = Request.Builder()
                        .url(url)
                        .header("X-API-Key", apiKey)
                        .post(body)
                        .build()

                    val curlLog = "curl -X POST '$url' -H 'X-API-Key: $apiKey' -H 'Content-Type: $JSON' -d '$json'"
                    Log.d("LocationService", "Queued Bluetooth Device Curl: $curlLog")

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            db.queuedBluetoothDeviceDao().markAsSent(listOf(device.id), System.currentTimeMillis())
                            val newBluetoothDevice = com.example.locationservice.BluetoothDevice(
                                name = device.name,
                                address = device.address,
                                description = device.description,
                                carId = device.carId
                            )
                            db.bluetoothDeviceDao().insert(newBluetoothDevice)
                            Log.d("LocationService", "Successfully sent and saved new bluetooth device: ${device.name}")
                        } else {
                            val errorBody = response.body?.string()
                            Log.w("LocationService", "SERVER ERROR ${response.code} for new bluetooth device at $url: Will retry on next interval. Body: $errorBody")
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationService", "NETWORK/DB error for new bluetooth device at $url – will retry later", e)
                return@launch
            }
        }
    }

    private fun cleanupOldSentData() {
        scope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(SENT_DATA_RETENTION_DAYS)
            db.locationDao().deleteSentBefore(cutoff)
            Log.d("LocationService", "Cleaned up sent records older than $SENT_DATA_RETENTION_DAYS days.")
        }
    }

    private fun cleanupOldSessionEvents() {
        scope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(SENT_DATA_RETENTION_DAYS)
            db.sessionEventDao().deleteSentBefore(cutoff)
            Log.d("LocationService", "Cleaned up sent session events older than $SENT_DATA_RETENTION_DAYS days.")
        }
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        releaseWakeLock()
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
