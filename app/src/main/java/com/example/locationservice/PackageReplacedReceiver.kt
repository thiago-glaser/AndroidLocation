package com.example.locationservice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        AppLogger.init(context)
        val action = intent?.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            AppLogger.w("PackageReplaced", "Ignoring unexpected action: $action")
            return
        }

        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) {
            null
        }
        val verName = pkgInfo?.versionName ?: "unknown"
        val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            pkgInfo?.versionCode?.toLong() ?: 0L
        }

        AppLogger.i(
            "PackageReplaced",
            "MY_PACKAGE_REPLACED received! App upgraded to v$verName (code $verCode)"
        )

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        AppLogger.i("PackageReplaced", "Permissions check: fine=$hasFine, coarse=$hasCoarse, background=$hasBackground")

        if (!hasFine && !hasCoarse) {
            AppLogger.w("PackageReplaced", "Location permissions missing. Showing resume notification.")
            showResumeTrackingNotification(context)
            return
        }

        // On Android 14+ (targetSdk 34+), starting a LOCATION foreground service from background
        // requires ACCESS_BACKGROUND_LOCATION.
        // If granted, we can immediately start the foreground service using the receiver context exemption!
        try {
            AppLogger.i("PackageReplaced", "Starting LocationLoggingService synchronously from receiver context...")
            val serviceIntent = Intent(context, LocationLoggingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            LocationLoggingService.scheduleWatchdogAlarm(context, 5000L)
            AppLogger.i("PackageReplaced", "LocationLoggingService start requested successfully.")
        } catch (t: Throwable) {
            AppLogger.e("PackageReplaced", "Failed to start service directly from receiver context", t)
            showResumeTrackingNotification(context)
        }
    }

    companion object {
        const val NOTIFICATION_ID_RESUME = 1001
        private const val CHANNEL_ID_RESUME = "app_update_resume_channel"

        fun showResumeTrackingNotification(context: Context) {
            try {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        CHANNEL_ID_RESUME,
                        "Service Status Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Alerts to resume tracking after app updates"
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    101,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, CHANNEL_ID_RESUME)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(context.getString(R.string.notif_update_resume_title))
                    .setContentText(context.getString(R.string.notif_update_resume_text))
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(context.getString(R.string.notif_update_resume_text))
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                notificationManager.notify(NOTIFICATION_ID_RESUME, notification)
            } catch (e: Exception) {
                AppLogger.e("PackageReplaced", "Failed to show resume notification", e)
            }
        }
    }
}

