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

class LocationLoggingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_logging_channel"
        private const val FILE_NAME = "locations.txt"
        private const val UPDATE_INTERVAL_MS = 60_000L   // 1 minute
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
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
                    Log.d("LocationService", "Location: ${location.latitude}, ${location.longitude}")
                    appendLocationToFile(location.latitude, location.longitude)
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

    private fun appendLocationToFile(lat: Double, lng: Double) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        val line = "$timestamp, $lat, $lng\n"

        // Internal storage -> files/locations.txt
        val file = File(filesDir, FILE_NAME)
        file.appendText(line)
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