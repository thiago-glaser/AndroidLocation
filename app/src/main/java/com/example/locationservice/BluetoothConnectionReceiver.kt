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
        val pendingResult = goAsync()
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingResult.finish()
            return
        }

        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val deviceName = device?.name ?: "Unknown Device"
        val deviceAddress = device?.address ?: "Unknown Address"

        val actionMessage = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> "connected"
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> "disconnected"
            else -> {
                pendingResult.finish()
                return
            }
        }

        val logMessage = "Device $actionMessage: $deviceName ($deviceAddress)"
        Log.d("BluetoothReceiver", logMessage)

        GlobalScope.launch {
            try {
                val db = LocationDatabase.getDatabase(context)

                if (deviceName == "myChevrolet") {
                    val eventType = if (actionMessage == "connected") "start" else "end"
                    val sessionEvent = SessionEvent(
                        deviceId = LocationLoggingService.getDeviceAndroidId(context),
                        eventType = eventType,
                        timestampUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.format(Date())
                    )
                    Log.d("BluetoothReceiver", "Queuing '${sessionEvent.eventType}' session event for device '${sessionEvent.deviceId}'")
                    db.sessionEventDao().insert(sessionEvent)
                    Log.d("BluetoothReceiver", "Successfully queued session event.")
                }

                val bluetoothDevices = db.bluetoothDeviceDao().getAll()
                val matchingDevice = bluetoothDevices.find { it.address == deviceAddress }
                val queuedDevice = db.queuedBluetoothDeviceDao().findByAddress(deviceAddress)

                if (matchingDevice == null && queuedDevice == null && actionMessage == "connected") {
                    val newDevice = QueuedBluetoothDevice(
                        name = deviceName,
                        address = deviceAddress,
                        description = "",
                        carId = ""
                    )
                    db.queuedBluetoothDeviceDao().insert(newDevice)
                    Log.d("BluetoothReceiver", "New bluetooth device queued for sending to server.")
                }
            } catch(e: Exception) {
                Log.e("BluetoothReceiver", "Failed to process bluetooth event", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}