package com.example.locationservice

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat

class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val deviceName = device?.name ?: "Unknown"

        if (deviceName != "Trax 2025") {
            Log.d("BluetoothReceiver", deviceName +" connected")
            return
        }

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.d("BluetoothReceiver", "Trax 2025 connected")
                ApiManager.startSession(LocationLoggingService.getDeviceAndroidId(context))
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.d("BluetoothReceiver", "Trax 2025 disconnected")
                ApiManager.endSession(LocationLoggingService.getDeviceAndroidId(context))
            }
        }
    }
}