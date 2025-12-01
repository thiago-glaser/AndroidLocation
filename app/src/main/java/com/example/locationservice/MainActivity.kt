package com.example.locationservice
import android.util.Log
import android.provider.Settings
import android.net.Uri
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.locationservice.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.all { it.value }
            Log.d("PERMISSION", "Permission result: $grants → All granted: $allGranted")

            if (allGranted) {
                binding.tvStatus.text = "Permission granted. Starting service..."
                startLocationService()
            } else {
                binding.tvStatus.text = "Location permission denied."
                showPermissionDeniedDialog()
            }
        }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Location permission is needed to log your position. Please enable it in Settings.")
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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!isAppLaunchedByUser()) {
            binding.tvStatus.text = "Warning: Open the app manually once after install/reboot to enable auto-start!"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
        binding.btnStart.setOnClickListener { checkAndRequestPermissions() }
        binding.btnStop.setOnClickListener { stopLocationService() }
    }

    private fun isAppLaunchedByUser(): Boolean {
        return (callingActivity != null || intent?.action == Intent.ACTION_MAIN)
    }

    private fun checkAndRequestPermissions() {
        Log.d("PERMISSION", "checkAndRequestPermissions() called")

        val permissions = mutableListOf<String>().apply {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            }
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        Log.d("PERMISSION", "Missing permissions: $missing")

        if (missing.isEmpty()) {
            Log.d("PERMISSION", "All permissions granted → Starting service")
            binding.tvStatus.text = "Permissions ready. Starting service..."
            startLocationService()  // THIS WILL NOW RUN
        } else {
            Log.d("PERMISSION", "Requesting missing permissions: $missing")
            binding.tvStatus.text = "Requesting location access..."
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
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
}