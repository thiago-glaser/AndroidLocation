package com.example.locationservice.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.core.content.ContextCompat
import com.example.locationservice.LocationLoggingService

class LocationCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        ensureLocationLoggingServiceRunning()
        return AutoSessionMapScreen(carContext)
    }

    private fun ensureLocationLoggingServiceRunning() {
        val hasFine = ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            try {
                val serviceIntent = Intent(carContext, LocationLoggingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    carContext.startForegroundService(serviceIntent)
                } else {
                    carContext.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.w("LocationCarSession", "Could not start LocationLoggingService from CarSession", e)
            }
        }
    }
}

