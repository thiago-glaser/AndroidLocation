package com.example.locationservice

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BluetoothConnectionReceiver : BroadcastReceiver() {

    companion object {
        private val eventMutex = Mutex()
    }

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.init(context)
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
        if (device == null) {
            pendingResult.finish()
            return
        }

        // Only process devices that are actually paired (bonded) with this phone!
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            AppLogger.d("BluetoothReceiver", "Ignoring Bluetooth event: device is not bonded (${device.address})")
            pendingResult.finish()
            return
        }

        val deviceName = device.name?.trim() ?: ""
        val deviceAddress = device.address?.trim() ?: ""

        if (deviceName.isEmpty() || deviceName.equals("Unknown Device", ignoreCase = true) || deviceAddress.isEmpty()) {
            AppLogger.d("BluetoothReceiver", "Ignoring Bluetooth device with unknown name or address: $deviceAddress")
            pendingResult.finish()
            return
        }

        val actionMessage = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> "connected"
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> "disconnected"
            else -> {
                pendingResult.finish()
                return
            }
        }

        val logMessage = "Device $actionMessage: $deviceName ($deviceAddress)"
        AppLogger.i("BluetoothReceiver", logMessage)

        GlobalScope.launch {
            try {
                eventMutex.withLock {
                    val db = LocationDatabase.getDatabase(context)
                    val eventType = if (actionMessage == "connected") "start" else "end"
                    val timestampUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(Date())

                    // Do not allow more than one session start in the same second for the user/device/car
                    if (eventType == "start") {
                        val latestStart = db.sessionEventDao().getLatestStartEvent()
                        val currentSecond = timestampUtc.take(19) // "yyyy-MM-dd'T'HH:mm:ss"
                        val latestSecond = latestStart?.timestampUtc?.take(19)
                        if (latestSecond != null && latestSecond == currentSecond) {
                            AppLogger.w(
                                "BluetoothReceiver",
                                "Ignoring duplicate session start in the same second ($currentSecond) for device/car ($deviceAddress)"
                            )
                            return@withLock
                        }
                    }

                    val sessionEvent = SessionEvent(
                        deviceId = LocationLoggingService.getDeviceAndroidId(context),
                        bluetoothAddress = deviceAddress,
                        eventType = eventType,
                        timestampUtc = timestampUtc
                    )
                    AppLogger.i("BluetoothReceiver", "Queuing session event for address '$deviceAddress' (Action: $actionMessage)")
                    db.sessionEventDao().insert(sessionEvent)
                    AppLogger.d("BluetoothReceiver", "Successfully queued session event.")

                    // Logic for discovery of new paired devices
                    val bluetoothDevices = db.bluetoothDeviceDao().getAll()
                    val matchingDevice = bluetoothDevices.find { it.address.equals(deviceAddress, ignoreCase = true) }
                    val queuedDevice = db.queuedBluetoothDeviceDao().findByAddress(deviceAddress)
                    if (matchingDevice == null && queuedDevice == null && actionMessage == "connected") {
                        val newDevice = QueuedBluetoothDevice(
                            name = deviceName,
                            address = deviceAddress,
                            description = "",
                            carId = ""
                        )
                        db.queuedBluetoothDeviceDao().insert(newDevice)
                        AppLogger.i("BluetoothReceiver", "New paired bluetooth device queued for sending to server: $deviceName")
                    }
                }

                // Ensure LocationLoggingService is running and flushes session event
                LocationLoggingService.ensureServiceRunning(context.applicationContext)
            } catch (e: Exception) {
                AppLogger.e("BluetoothReceiver", "Failed to process bluetooth event", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
