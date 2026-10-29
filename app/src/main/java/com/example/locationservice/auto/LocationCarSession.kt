package com.example.locationservice.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.example.locationservice.LocationLoggingService

class LocationCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        LocationLoggingService.ensureServiceRunning(carContext)
        return AutoSessionMapScreen(carContext)
    }
}
