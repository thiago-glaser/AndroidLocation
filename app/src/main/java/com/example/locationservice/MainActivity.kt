package com.example.locationservice
import android.util.Log
import android.provider.Settings
import android.net.Uri
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: LocationDatabase

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val fgsLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions[Manifest.permission.FOREGROUND_SERVICE_LOCATION] ?: false
        } else true
        val bluetoothScanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false
        val bluetoothConnectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false

        if (fineGranted && fgsLocationGranted && bluetoothScanGranted && bluetoothConnectGranted) {
            binding.tvStatus.text = "All required permissions granted → Service starting..."
            startLocationService()
        } else {
            binding.tvStatus.text = "Missing critical permission(s)!"
            Toast.makeText(this, "You must grant location and bluetooth permissions", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Location and Bluetooth permissions are needed. Please enable it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestPermissions();
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = LocationDatabase.getDatabase(this)

        if (!isAppLaunchedByUser()) {
            binding.tvStatus.text = "Warning: Open the app manually once after install/reboot to enable auto-start!"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
        binding.btnStart.setOnClickListener { requestPermissions() }
        binding.btnStop.setOnClickListener { stopLocationService() }
        binding.btnUpdateApiKey.setOnClickListener { showUpdateApiKeyDialog() }
    }

    private fun isAppLaunchedByUser(): Boolean {
        return (callingActivity != null || intent?.action == Intent.ACTION_MAIN)
    }

    private fun startLocationService() {
        Log.d("SERVICE", "startLocationService() called")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("SERVICE", "Permission OK → Starting foreground service")
            val intent = Intent(this, LocationLoggingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            binding.tvStatus.text = "Service started. Logging every few seconds."
            requestIgnoreBatteryOptimizations() // optional but recommended
        } else {
            Log.e("SERVICE", "Permission DENIED → Cannot start")
            binding.tvStatus.text = "ERROR: Location permission missing!"
            showPermissionDeniedDialog()
        }
    }

    private fun stopLocationService() {
        val intent = Intent(this, LocationLoggingService::class.java)
        stopService(intent)
        binding.tvStatus.text = "Service stopped"
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // Show a nice dialog first (optional but highly recommended on Pixel)
                AlertDialog.Builder(this)
                    .setTitle("Battery optimisation")
                    .setMessage("For reliable auto-start after reboot on Pixel phones, please select \"Unrestricted\" or at least \"Not optimised\" in the next screen.")
                    .setPositiveButton("Open settings") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            // Some Pixel devices block this intent → fall back to the full battery settings page
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun requestPermissions() {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )

        // THIS LINE IS THE ONE THAT FIXES IT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            list.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        permissionLauncher.launch(list.toTypedArray())
    }

    private fun showUpdateApiKeyDialog() {
        val editText = EditText(this)
        lifecycleScope.launch {
            val apiKey = db.settingDao().getSetting("api_key")?.value ?: ""
            editText.setText(apiKey)
        }

        AlertDialog.Builder(this)
            .setTitle("Update API Key")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newApiKey = editText.text.toString()
                lifecycleScope.launch {
                    db.settingDao().insert(Setting("api_key", newApiKey))
                    Toast.makeText(this@MainActivity, "API Key updated", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}