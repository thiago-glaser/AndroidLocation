package com.example.locationservice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // Extra safety: check if it's our package (for PACKAGE_REPLACED)
            if (intent.action == Intent.ACTION_PACKAGE_REPLACED) {
                val data = intent.dataString ?: return
                if (!data.contains(context.packageName)) return
            }

            if (hasLocationPermission(context)) {
                startLocationService(context)
            } else {
                Log.w("BootReceiver", "No location permission - cannot start service on boot")
            }
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fineOk = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        val fgsLocationOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

        return fineOk && fgsLocationOk
    }

    private fun startLocationService(context: Context) {
        val serviceIntent = Intent(context, LocationLoggingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d("BootReceiver", "Location service started after boot")
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start service", e)
        }
    }
}