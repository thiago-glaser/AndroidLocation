package com.example.locationservice

import android.Manifest
import android.app.AlarmManager
import android.os.SystemClock
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
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
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
        private const val UPDATE_INTERVAL_MS = 15_000L // 15s interval = ~20 locations per 5 minutes
        private const val MIN_LOG_INTERVAL_MS = 12_500L // Rate-limit / deduplication threshold
        private const val MAX_ACCURACY_METERS = 25.0f // Only accept high-accuracy GPS fixes
        private const val SEND_BATCH_INTERVAL_MS = 5 * 60 * 1000L // Fixed 5 minutes interval (independent of session state)
        private const val BLUETOOTH_REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L // 12 hours
        private val SENT_DATA_RETENTION_DAYS = 60L
        private const val API_BASE_URL = "https://travel-access.ddns.net/api"
        private const val API_KEY_SETTING = "api_key"
        const val ACTION_FLUSH_QUEUE = "com.example.locationservice.ACTION_FLUSH_QUEUE"
        const val ACTION_SESSION_STATE_CHANGED = "com.example.locationservice.ACTION_SESSION_STATE_CHANGED"

        fun triggerFlush(context: Context) {
            try {
                val intent = Intent(context, LocationLoggingService::class.java).apply {
                    action = ACTION_FLUSH_QUEUE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.e("LocationService", "Failed to trigger queue flush", e)
            }
        }

        fun notifySessionStateChanged(context: Context) {
            try {
                val intent = Intent(context, LocationLoggingService::class.java).apply {
                    action = ACTION_SESSION_STATE_CHANGED
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AppLogger.e("LocationService", "Failed to trigger session state notification", e)
            }
        }

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

        private const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val WATCHDOG_REQUEST_CODE = 9988

        fun scheduleWatchdogAlarm(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            try {
                AppLogger.init(context)
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, ServiceWatchdogReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    WATCHDOG_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerAt = SystemClock.elapsedRealtime() + delayMs
                val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
                if (canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                    AppLogger.d("LocationService", "Exact watchdog alarm scheduled in ${delayMs / 1000}s")
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                    AppLogger.d("LocationService", "Inexact watchdog alarm scheduled in ${delayMs / 1000}s")
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                    AppLogger.d("LocationService", "Watchdog alarm scheduled in ${delayMs / 1000}s")
                }
            } catch (e: Exception) {
                AppLogger.w("LocationService", "Failed to schedule watchdog alarm", e)
            }
        }

        fun ensureServiceRunning(context: Context) {
            AppLogger.init(context)
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                AppLogger.w("LocationService", "Cannot start LocationLoggingService: location permission not granted.")
                return
            }

            try {
                val serviceIntent = Intent(context, LocationLoggingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                scheduleWatchdogAlarm(context)
            } catch (e: Throwable) {
                AppLogger.e("LocationService", "Could not start LocationLoggingService from background; scheduling fast watchdog", e)
                scheduleWatchdogAlarm(context, 3000L)
            }
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var locationManager: LocationManager? = null
    private var nativeLocationListener: LocationListener? = null
    private var lastLocationFix: Location? = null
    @Volatile
    private var isCurrentlyInSession = false
    @Volatile
    private var currentTrackingMode = "SESSION_ONLY"
    private var locationTimerJob: Job? = null
    @Volatile
    private var lastHandledFixTimeMs = 0L
    @Volatile
    private var lastStaleKickTimeMs = 0L
    @Volatile
    private var lastRestartTimeMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private lateinit var db: LocationDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var periodicJob: Job? = null
    private var lastBluetoothFetchTime = 0L

    @Volatile
    private var hasSentInitialBatch = false

    @Volatile
    private var lastBatchSentTimeMs = 0L

    private fun tryStartForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification)
                AppLogger.i("LocationService", "startForeground succeeded (pre-Q)")
            } catch (t: Throwable) {
                AppLogger.e("LocationService", "Fatal error in tryStartForeground (pre-Q)", t)
            }
            return
        }

        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasLocationPerm = hasFine || hasCoarse

        val hasBtConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val candidateTypes = mutableListOf<Pair<String, Int>>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (hasLocationPerm && hasBtConnect) {
                candidateTypes.add(
                    "LOCATION_AND_CONNECTED_DEVICE" to (ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                )
            }
            if (hasLocationPerm) {
                candidateTypes.add("LOCATION" to ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            }
            if (hasBtConnect) {
                candidateTypes.add("CONNECTED_DEVICE" to ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else {
            candidateTypes.add("LOCATION" to ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }

        for ((label, fgsType) in candidateTypes) {
            try {
                startForeground(NOTIFICATION_ID, notification, fgsType)
                AppLogger.i("LocationService", "startForeground succeeded with type=$label ($fgsType)")
                return
            } catch (t: Throwable) {
                AppLogger.w("LocationService", "startForeground failed with type=$label ($fgsType): ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        try {
            startForeground(NOTIFICATION_ID, notification)
            AppLogger.i("LocationService", "startForeground succeeded with fallback call")
        } catch (t: Throwable) {
            AppLogger.e("LocationService", "All startForeground attempts failed", t)
            PackageReplacedReceiver.showResumeTrackingNotification(this)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i("LocationService", "onCreate: initializing LocationLoggingService")
        createSessionEventNotificationChannel()
        tryStartForeground()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        db = LocationDatabase.getDatabase(this)
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
                AppLogger.d("LocationService", "Partial WakeLock acquired")
            }
        } catch (e: Exception) {
            AppLogger.w("LocationService", "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.d("LocationService", "Partial WakeLock released")
            }
        } catch (e: Exception) {
            AppLogger.w("LocationService", "Failed to release WakeLock", e)
        }
    }

    private suspend fun fetchBluetoothDevices() {
        val apiKey = db.settingDao().getSetting(API_KEY_SETTING)?.value?.takeIf { it.isNotEmpty() } ?: ApiClient(this@LocationLoggingService).ensureApiKey()
        if (apiKey.isNullOrEmpty()) {
            AppLogger.w("LocationService", "API key is not set. Cannot fetch bluetooth devices.")
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
                    AppLogger.d("LocationService", "Bluetooth API Response received (${responseBody?.length ?: 0} bytes)")
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
                            AppLogger.i("LocationService", "Successfully fetched and saved ${devices.size} bluetooth devices.")
                        } catch (e: JSONException) {
                            AppLogger.e("LocationService", "Failed to parse bluetooth devices response", e)
                        }
                    }
                } else {
                    val errorBody = response.body?.string()
                    AppLogger.w("LocationService", "Failed to fetch bluetooth devices: ${response.code} at ${request.url}. Body: $errorBody")
                }
            }
        } catch (e: Exception) {
            AppLogger.e("LocationService", "Failed to fetch bluetooth devices", e)
        }
    }

    private suspend fun getTrackingMode(): String {
        val saved = db.settingDao().getSetting("tracking_mode")?.value?.uppercase(Locale.US)
        val mode = if (saved == "ALL_TIME") "ALL_TIME" else "SESSION_ONLY"
        currentTrackingMode = mode
        return mode
    }

    private suspend fun checkIsInSession(): Boolean {
        val latestEvent = db.sessionEventDao().getLatestEvent()
        if (latestEvent != null) {
            val localActive = latestEvent.eventType == "start"
            isCurrentlyInSession = localActive
            return localActive
        }
        return isCurrentlyInSession
    }

    private fun startLocationTimer() {
        if (locationTimerJob?.isActive == true) return

        locationTimerJob = scope.launch {
            AppLogger.i("LocationService", "Starting location evaluation timer (UPDATE_INTERVAL_MS=${UPDATE_INTERVAL_MS}ms)")
            while (isActive) {
                delay(UPDATE_INTERVAL_MS)

                val mode = getTrackingMode()
                val inSession = checkIsInSession()

                if (mode == "SESSION_ONLY") {
                    if (!inSession) {
                        // VALIDATE: not in session! Do NOT try to get location!
                        if (locationCallback != null || nativeLocationListener != null) {
                            withContext(Dispatchers.Main) {
                                stopLocationUpdates()
                            }
                            AppLogger.d("LocationService", "Timer: NOT in session (trackingMode=SESSION_ONLY). Paused location providers.")
                        }
                        continue
                    }

                    // In session: validate true -> ensure providers running
                    if (locationCallback == null && nativeLocationListener == null) {
                        AppLogger.i("LocationService", "Timer: IN ACTIVE SESSION! Starting location providers.")
                        withContext(Dispatchers.Main) {
                            startLocationUpdates(forceRestart = false)
                        }
                    }

                    // Only ask for on-demand fix if regular stream hasn't produced a fix recently
                    val timeSinceLastFix = System.currentTimeMillis() - lastHandledFixTimeMs
                    if (timeSinceLastFix >= UPDATE_INTERVAL_MS) {
                        requestImmediateFix("timer_in_session")
                    }
                } else {
                    // ALL_TIME mode: keep working as is!
                    if (locationCallback == null && nativeLocationListener == null) {
                        withContext(Dispatchers.Main) {
                            startLocationUpdates(forceRestart = false)
                        }
                    }
                    val timeSinceLastFix = System.currentTimeMillis() - lastHandledFixTimeMs
                    if (timeSinceLastFix >= UPDATE_INTERVAL_MS) {
                        requestImmediateFix("timer_all_time")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.init(this)
        AppLogger.i("LocationService", "onStartCommand: Ensuring foreground running (flags=$flags, startId=$startId, action=${intent?.action})")
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }

        tryStartForeground()
        acquireWakeLock()

        startLocationTimer()
        startPeriodicSending()
        scheduleWatchdogAlarm(this)

        scope.launch {
            val mode = getTrackingMode()
            val inSession = checkIsInSession()

            withContext(Dispatchers.Main) {
                if (mode == "ALL_TIME" || inSession) {
                    val now = System.currentTimeMillis()
                    val timeSinceLastFix = if (lastHandledFixTimeMs > 0L) now - lastHandledFixTimeMs else Long.MAX_VALUE
                    val isStalled = timeSinceLastFix >= 60_000L

                    if (isStalled) {
                        AppLogger.w("LocationService", "onStartCommand: Stalled or new session (timeSinceLastFix=${if (lastHandledFixTimeMs == 0L) "none" else "${timeSinceLastFix / 1000}s"}). Forcing location restart.")
                        startLocationUpdates(forceRestart = true)
                    } else {
                        startLocationUpdates(forceRestart = false)
                    }
                    requestImmediateFix("on_start_command")
                } else {
                    AppLogger.d("LocationService", "onStartCommand: Not in session (trackingMode=SESSION_ONLY). Location listeners idle.")
                    stopLocationUpdates()
                }
            }
        }

        if (intent?.action == ACTION_FLUSH_QUEUE) {
            flushQueuedDataInOrder()
        }

        return START_STICKY
    }

    private fun startPeriodicSending() {
        if (periodicJob?.isActive == true) {
            // Already running periodic loop; do not flush or duplicate
            return
        }

        periodicJob = scope.launch {
            // Initial fetch & flush
            ApiClient(this@LocationLoggingService).ensureDeviceRegistered()
            fetchBluetoothDevices()
            flushQueuedDataInOrder()

            // Also check latest session status from server on startup
            try {
                val latestSessionRes = ApiClient(this@LocationLoggingService).getLatestSession()
                latestSessionRes.onSuccess { session ->
                    val serverSessionActive = session != null && session.endTime.isNullOrEmpty()
                    val localEvent = db.sessionEventDao().getLatestEvent()
                    val localActive = localEvent?.eventType == "start"
                    isCurrentlyInSession = localActive || (localEvent == null && serverSessionActive)
                }
            } catch (_: Exception) {}

            val pollInterval = 5000L // check every 5 seconds

            while (isActive) {
                delay(pollInterval)
                val now = System.currentTimeMillis()

                // Health check: detect stalled location stream only when tracking is active
                if (currentTrackingMode == "ALL_TIME" || isCurrentlyInSession) {
                    val timeSinceLastFix = if (lastHandledFixTimeMs > 0L) now - lastHandledFixTimeMs else Long.MAX_VALUE
                    if (timeSinceLastFix >= 45_000L && (now - lastStaleKickTimeMs >= 30_000L)) {
                        lastStaleKickTimeMs = now
                        AppLogger.w("LocationService", "Watchdog tick: no location fix for ${if (lastHandledFixTimeMs == 0L) "initial" else "${timeSinceLastFix / 1000}s"}. Requesting on-demand fix.")
                        requestImmediateFix("periodic_stale_kick")
                    }
                    if (timeSinceLastFix >= 120_000L && (now - lastRestartTimeMs >= 120_000L)) {
                        lastRestartTimeMs = now
                        AppLogger.w("LocationService", "Watchdog tick: location stream stalled for ${timeSinceLastFix / 1000}s! Restarting providers.")
                        withContext(Dispatchers.Main) {
                            startLocationUpdates(forceRestart = true)
                        }
                    }
                }

                if (now - lastBatchSentTimeMs >= SEND_BATCH_INTERVAL_MS) {
                    lastBatchSentTimeMs = now
                    AppLogger.d("LocationService", "Fixed interval reached (${SEND_BATCH_INTERVAL_MS / 1000}s), sending batch and cleaning up old data.")

                    // Refresh bluetooth devices if interval exceeded
                    if (now - lastBluetoothFetchTime > BLUETOOTH_REFRESH_INTERVAL_MS) {
                        ApiClient(this@LocationLoggingService).ensureDeviceRegistered()
                        fetchBluetoothDevices()
                    }

                    flushQueuedDataInOrder()
                    cleanupOldSentData()
                    cleanupOldSessionEvents()
                }
            }
        }
    }

    private fun handleLocationFix(location: Location, providerName: String = "") {
        if (currentTrackingMode == "SESSION_ONLY" && !isCurrentlyInSession) {
            AppLogger.d("LocationService", "Dropped location fix: device is not in an active session (trackingMode=SESSION_ONLY)")
            return
        }

        if (location.latitude == 0.0 && location.longitude == 0.0) {
            AppLogger.w("LocationService", "Dropped (0,0) location fix")
            return
        }

        // Never accept coarse network provider triangulation (cell-tower/Wi-Fi jumps)
        val prov = (location.provider ?: "").lowercase(Locale.ROOT)
        if (prov.contains("network")) {
            AppLogger.w("LocationService", "Dropped network-provider fix (coarse cell/wifi)")
            return
        }

        // Enforce high accuracy: reject any fix with accuracy worse than 25m or missing accuracy
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METERS) {
            AppLogger.w("LocationService", "Dropped low-accuracy fix (${if (location.hasAccuracy()) "${location.accuracy}m" else "none"} > ${MAX_ACCURACY_METERS}m, provider=$prov)")
            return
        }

        // Reject stale fixes older than 30 seconds
        val now = System.currentTimeMillis()
        if (location.time > 0 && (now - location.time) > 30_000L) {
            AppLogger.d("LocationService", "Dropped stale location fix (age=${(now - location.time) / 1000}s)")
            return
        }

        val speedKmh = computeSpeedKmh(location)
        val altMeters = if (location.hasAltitude() && location.altitude != 0.0) {
            location.altitude
        } else {
            _liveTelemetry.value?.altitudeMeters ?: 0.0
        }

        // Always update live telemetry for UI (speedometer, live map/status)
        _liveTelemetry.value = LiveGpsTelemetry(
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = speedKmh,
            altitudeMeters = altMeters,
            timestampMs = location.time
        )

        // Rate-limiting / deduplication: target ~20 points per 5 min (~1 fix every 13-15s)
        val timeSinceLastFix = now - lastHandledFixTimeMs
        if (lastLocationFix != null && timeSinceLastFix < MIN_LOG_INTERVAL_MS) {
            AppLogger.d("LocationService", "Skipped duplicate/too-frequent fix ($timeSinceLastFix ms < ${MIN_LOG_INTERVAL_MS}ms, provider=$providerName)")
            return
        }

        lastHandledFixTimeMs = now
        lastLocationFix = location

        val src = if (providerName.isNotEmpty()) " [$providerName]" else ""
        AppLogger.d(
            "LocationService",
            String.format(
                Locale.US,
                "GPS%s (acc=%.1fm): %.7f, %.7f, Alt: %.1fm, Speed: %.1f km/h",
                src,
                location.accuracy,
                location.latitude,
                location.longitude,
                altMeters,
                speedKmh
            )
        )
        queueLocation(location)
    }

    private fun startLocationUpdates(forceRestart: Boolean = false) {
        if (currentTrackingMode == "SESSION_ONLY" && !isCurrentlyInSession) {
            AppLogger.d("LocationService", "startLocationUpdates: Skipped because trackingMode=SESSION_ONLY and not in session.")
            return
        }

        if (!forceRestart && (locationCallback != null || nativeLocationListener != null)) {
            AppLogger.d("LocationService", "Location updates already registered")
            return
        }

        if (forceRestart) {
            AppLogger.i("LocationService", "Force restarting location listeners...")
            stopLocationUpdates()
        }

        // 1. Google Play Services Fused Location Provider
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(10_000L)
            .setWaitForAccurateLocation(true)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleLocationFix(location, "Fused")
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            locationCallback = callback
            AppLogger.i("LocationService", "Fused location updates requested successfully (interval=${UPDATE_INTERVAL_MS}ms)")
        } catch (e: SecurityException) {
            AppLogger.w("LocationService", "Fused location updates permission temporarily denied", e)
        } catch (e: Exception) {
            AppLogger.e("LocationService", "Failed to request fused location updates", e)
        }

        // 2. Native Android LocationManager (GPS Provider only, no Network) as dual-redundant engine
        startNativeLocationManagerUpdates()

        // 3. Request immediate fix so first point arrives right away
        requestImmediateFix("startup_or_restart")
    }

    private fun startNativeLocationManagerUpdates() {
        try {
            if (locationManager == null) {
                locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            }
            val lm = locationManager ?: return

            if (nativeLocationListener == null) {
                nativeLocationListener = LocationListener { location ->
                    handleLocationFix(location, "Native-" + (location.provider ?: "GPS"))
                }
            }
            val listener = nativeLocationListener ?: return

            try {
                lm.removeUpdates(listener)
            } catch (_: Exception) {}

            var registeredAny = false
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                AppLogger.i("LocationService", "Native GPS_PROVIDER updates registered (interval=${UPDATE_INTERVAL_MS}ms)")
                registeredAny = true
            } else {
                AppLogger.w("LocationService", "Native GPS_PROVIDER is not enabled on device")
            }

            if (!registeredAny) {
                AppLogger.w("LocationService", "Native GPS provider is not enabled on device!")
            }
        } catch (e: SecurityException) {
            AppLogger.w("LocationService", "Native LocationManager permission denied", e)
        } catch (e: Exception) {
            AppLogger.e("LocationService", "Failed to register native LocationManager updates", e)
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationCallback?.let {
                fusedLocationClient.removeLocationUpdates(it)
                AppLogger.d("LocationService", "Fused location updates removed")
            }
        } catch (e: Exception) {
            AppLogger.w("LocationService", "Error removing fused location updates", e)
        }
        locationCallback = null

        try {
            nativeLocationListener?.let {
                locationManager?.removeUpdates(it)
                AppLogger.d("LocationService", "Native location updates removed")
            }
        } catch (e: Exception) {
            AppLogger.w("LocationService", "Error removing native location updates", e)
        }
        nativeLocationListener = null
    }

    private fun requestImmediateFix(reason: String) {
        if (currentTrackingMode == "SESSION_ONLY" && !isCurrentlyInSession) {
            AppLogger.d("LocationService", "requestImmediateFix: Skipped ($reason) because trackingMode=SESSION_ONLY and not in session.")
            return
        }
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        AppLogger.i("LocationService", "Immediate fix acquired ($reason)")
                        handleLocationFix(loc, "Immediate")
                    } else {
                        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                            if (lastLoc != null && (System.currentTimeMillis() - lastLoc.time) < 30 * 1000L) {
                                AppLogger.i("LocationService", "Recent lastLocation fix acquired ($reason)")
                                handleLocationFix(lastLoc, "LastLocation")
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    AppLogger.w("LocationService", "Failed to get immediate location fix ($reason)", e)
                }
        } catch (e: SecurityException) {
            AppLogger.w("LocationService", "Permission denied for immediate fix ($reason)", e)
        } catch (e: Exception) {
            AppLogger.e("LocationService", "Exception requesting immediate fix ($reason)", e)
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
                AppLogger.d("LocationService", "NEW LOCATION QUEUED: $utcTime | ${location.latitude}, ${location.longitude}")

                // Immediately flush the first GPS point after service start
                if (!hasSentInitialBatch) {
                    hasSentInitialBatch = true
                    flushQueuedDataInOrder()
                }
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                AppLogger.d("LocationService", "DUPLICATE IGNORED (same timestamp): $utcTime")
            } catch (e: Exception) {
                AppLogger.e("LocationService", "Failed to queue location", e)
            }
        }
    }

    private val dataSyncMutex = Mutex()
    private val pendingFlush = AtomicBoolean(false)

    fun flushQueuedDataInOrder() {
        scope.launch {
            pendingFlush.set(true)
            if (!dataSyncMutex.tryLock()) {
                AppLogger.d("LocationService", "Queue flush already in progress; marked pending.")
                return@launch
            }
            try {
                while (pendingFlush.getAndSet(false)) {
                    val apiKey = db.settingDao().getSetting(API_KEY_SETTING)?.value?.takeIf { it.isNotEmpty() }
                        ?: ApiClient(this@LocationLoggingService).ensureApiKey()
                    if (apiKey.isNullOrEmpty()) {
                        AppLogger.w("LocationService", "API key is not set. Cannot flush queued data.")
                        return@launch
                    }

                    // 1. Drain all queued locations first
                    val locationsDrained = drainQueuedLocationsSync(apiKey)
                    if (!locationsDrained) {
                        AppLogger.w("LocationService", "Location draining incomplete or failed. Postponing session events to preserve ordering.")
                        return@launch
                    }

                    // 2. Drain all queued session events in chronological order
                    drainQueuedSessionEventsSync(apiKey)

                    // 3. Drain queued bluetooth devices
                    drainQueuedBluetoothDevicesSync(apiKey)
                }
            } catch (e: Exception) {
                AppLogger.e("LocationService", "Error in flushQueuedDataInOrder", e)
            } finally {
                dataSyncMutex.unlock()
            }
        }
    }

    private suspend fun drainQueuedLocationsSync(apiKey: String): Boolean {
        val batchSize = 500
        while (true) {
            val locations = db.locationDao().getUnsentBatch(batchSize)
            if (locations.isEmpty()) {
                if (lastBatchSentTimeMs == 0L) {
                    lastBatchSentTimeMs = System.currentTimeMillis()
                }
                AppLogger.d("LocationService", "Location queue empty.")
                return true
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

            AppLogger.d("LocationService", "Sending ${locations.size} location(s) to $url")

            val success = try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    AppLogger.d("LocationService", "HTTP Response Code: $code")
                    if (response.isSuccessful) {
                        val sentIds = locations.map { it.id }
                        val now = System.currentTimeMillis()
                        db.locationDao().markAsSent(sentIds, now)
                        hasSentInitialBatch = true
                        lastBatchSentTimeMs = now
                        AppLogger.i("LocationService", "SUCCESS: Sent & marked batch of ${locations.size} locations.")
                        true
                    } else {
                        val errorBody = response.body?.string()
                        AppLogger.w("LocationService", "SERVER ERROR $code at $url: Body: $errorBody")
                        false
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("LocationService", "NETWORK/DB error sending locations to $url", e)
                false
            }

            if (!success) {
                return false
            }
        }
    }

    private suspend fun drainQueuedSessionEventsSync(apiKey: String): Boolean {
        while (true) {
            val events = db.sessionEventDao().getUnsentBatch(100)
            if (events.isEmpty()) {
                return true
            }

            val seenStartSeconds = mutableSetOf<String>()

            for (event in events) {
                if (event.eventType == "start") {
                    val secondKey = event.timestampUtc.take(19)
                    if (!seenStartSeconds.add(secondKey)) {
                        AppLogger.w("LocationService", "Skipping duplicate start session event in the same second ($secondKey)")
                        db.sessionEventDao().markAsSent(listOf(event.id), System.currentTimeMillis())
                        continue
                    }
                }

                val endpoint = when (event.eventType) {
                    "start" -> "start-session"
                    "end" -> "end-session"
                    else -> "start-session"
                }
                val json = JSONObject().apply {
                    put("device_id", event.deviceId)
                    put("bluetooth_address", event.bluetoothAddress)
                    put("timestamp_utc", event.timestampUtc)
                    if (!event.carId.isNullOrEmpty()) {
                        put("car_id", event.carId)
                    }
                    if (!event.sessionId.isNullOrEmpty()) {
                        put("id", event.sessionId)
                    }
                }.toString()
                val body = json.toRequestBody(JSON)
                val url = "$API_BASE_URL/Session/$endpoint"
                val request = Request.Builder()
                    .url(url)
                    .header("X-API-Key", apiKey)
                    .post(body)
                    .build()

                AppLogger.i("LocationService", "Sending session event (${event.eventType}) to $url. Payload: $json")

                val success = try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            db.sessionEventDao().markAsSent(listOf(event.id), System.currentTimeMillis())
                            AppLogger.i("LocationService", "Session event ${event.eventType} sent successfully.")
                            if (event.bluetoothAddress.isNotBlank()) {
                                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                val notification = NotificationCompat.Builder(this@LocationLoggingService, SESSION_EVENT_CHANNEL_ID)
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle(getString(R.string.notif_bt_event_queued))
                                    .setContentText(getString(R.string.notif_bt_address, event.bluetoothAddress))
                                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                    .setAutoCancel(true)
                                    .build()
                                notificationManager.notify(event.id.toInt(), notification)
                            }
                            true
                        } else {
                            val errorBody = response.body?.string()
                            AppLogger.w("LocationService", "SERVER ERROR ${response.code} for session event at $url: Body: $errorBody")
                            false
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e("LocationService", "NETWORK/DB error for session event at $url", e)
                    false
                }

                if (!success) {
                    // Stop processing the batch on failure to maintain order
                    return false
                }
            }
        }
    }

    private suspend fun drainQueuedBluetoothDevicesSync(apiKey: String) {
        val devices = db.queuedBluetoothDeviceDao().getUnsent()
        if (devices.isEmpty()) return

        for (device in devices) {
            if (device.name.isNullOrBlank() || device.name.equals("Unknown Device", ignoreCase = true) || device.address.isNullOrBlank()) {
                AppLogger.d("LocationService", "Skipping and discarding invalid/unknown queued bluetooth device (${device.address})")
                db.queuedBluetoothDeviceDao().markAsSent(listOf(device.id), System.currentTimeMillis())
                continue
            }

            val json = JSONObject()
                .put("name", device.name)
                .put("address", device.address)
                .put("description", device.description)
                .put("carId", device.carId)
                .toString()

            val body = json.toRequestBody(JSON)
            val url = "$API_BASE_URL/user/bluetooth"
            val request = Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey)
                .post(body)
                .build()

            try {
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
                        AppLogger.i("LocationService", "Successfully sent and saved new bluetooth device: ${device.name}")
                    } else {
                        val errorBody = response.body?.string()
                        AppLogger.w("LocationService", "SERVER ERROR ${response.code} for new bluetooth device at $url: Body: $errorBody")
                        return
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("LocationService", "NETWORK/DB error for new bluetooth device at $url", e)
                return
            }
        }
    }

    private fun cleanupOldSentData() {
        scope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(SENT_DATA_RETENTION_DAYS)
            db.locationDao().deleteSentBefore(cutoff)
            AppLogger.d("LocationService", "Cleaned up sent records older than $SENT_DATA_RETENTION_DAYS days.")
        }
    }

    private fun cleanupOldSessionEvents() {
        scope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(SENT_DATA_RETENTION_DAYS)
            db.sessionEventDao().deleteSentBefore(cutoff)
            AppLogger.d("LocationService", "Cleaned up sent session events older than $SENT_DATA_RETENTION_DAYS days.")
        }
    }

    override fun onDestroy() {
        AppLogger.w("LocationService", "onDestroy: LocationLoggingService stopped. Rescheduling watchdog resurrection...")
        locationTimerJob?.cancel()
        locationTimerJob = null
        stopLocationUpdates()
        releaseWakeLock()
        scope.cancel()
        scheduleWatchdogAlarm(this, 3000L)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_logger_active))
            .setContentText(getString(R.string.notif_collecting_gps))
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
