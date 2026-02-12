package com.example.locationservice

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val deviceName = device?.name ?: "Unknown Device"

        val actionMessage = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> "connected"
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> "disconnected"
            else -> return
        }

        val logMessage = "Device $actionMessage: $deviceName"
        Log.d("BluetoothReceiver", logMessage)

        // Specific logic for Trax 2025
        if (deviceName == "Trax 2025") {
            val eventType = if (actionMessage == "connected") "start" else "end"
            val sessionEvent = SessionEvent(
                deviceId = LocationLoggingService.getDeviceAndroidId(context),
                eventType = eventType,
                timestampUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(Date())
            )
            GlobalScope.launch {
                val db = LocationDatabase.getDatabase(context)
                db.sessionEventDao().insert(sessionEvent)
            }
        }
    }
}