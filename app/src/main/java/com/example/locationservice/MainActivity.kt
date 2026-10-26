package com.example.locationservice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            updateStatusText("All required permissions granted → Service starting...")
            startLocationService()
        } else {
            updateStatusText("Missing critical permission(s)!")
            Toast.makeText(this, "You must grant location and bluetooth permissions", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = LocationDatabase.getDatabase(this)

        requestPermissions()

        if (!isAppLaunchedByUser()) {
            updateStatusText("Warning: Open the app manually once after install/reboot to enable auto-start!")
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        setupFeatureNavigation()
        setupSettingsControls()
        refreshApiKeyStatus()
    }

    private fun setupFeatureNavigation() {
        // Native feature screen launches
        binding.btnNavSessions.setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }

        binding.btnNavCars.setOnClickListener {
            startActivity(Intent(this, CarsActivity::class.java))
        }

        binding.btnNavBluetooth.setOnClickListener {
            startActivity(Intent(this, BluetoothActivity::class.java))
        }

        binding.btnNavFuel.setOnClickListener {
            startActivity(Intent(this, FuelActivity::class.java))
        }

        binding.btnNavExpenses.setOnClickListener {
            startActivity(Intent(this, ExpensesActivity::class.java))
        }

        binding.btnNavReports.setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }

        // Main page button to open Settings
        binding.btnToggleSettings.setOnClickListener {
            binding.layoutMain.visibility = View.GONE
            binding.settingsLayout.visibility = View.VISIBLE
            refreshApiKeyStatus()
        }

        // Return from Settings back to Main page
        val returnToMain = {
            binding.settingsLayout.visibility = View.GONE
            binding.layoutMain.visibility = View.VISIBLE
        }

        binding.btnHideSettings.setOnClickListener { returnToMain() }
        binding.btnReturnMain.setOnClickListener { returnToMain() }

        // Handle hardware / gesture back button when settings is open
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.settingsLayout.visibility == View.VISIBLE) {
                    returnToMain()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupSettingsControls() {
        binding.btnStart.setOnClickListener { requestPermissions() }
        binding.btnStop.setOnClickListener { stopLocationService() }
        binding.btnUpdateApiKey.setOnClickListener { showUpdateApiKeyDialog() }
    }

    private fun updateStatusText(message: String) {
        binding.tvStatus.text = message
        binding.tvMainSubtitle.text = message
    }

    private fun refreshApiKeyStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = db.settingDao().getSetting("api_key")?.value
            withContext(Dispatchers.Main) {
                if (!apiKey.isNullOrEmpty()) {
                    val masked = if (apiKey.length > 8) {
                        "${apiKey.take(4)}••••${apiKey.takeLast(4)}"
                    } else {
                        "••••••••"
                    }
                    binding.tvApiKeyStatus.text = "Configured ($masked)"
                    binding.tvApiKeyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                } else {
                    binding.tvApiKeyStatus.text = "Not set (Please click Update API Key)"
                    binding.tvApiKeyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                }
            }
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
            updateStatusText("Service started. Logging every few seconds.")
            requestIgnoreBatteryOptimizations()
        } else {
            Log.e("SERVICE", "Permission DENIED → Cannot start")
            updateStatusText("ERROR: Location permission missing!")
            showPermissionDeniedDialog()
        }
    }

    private fun stopLocationService() {
        val intent = Intent(this, LocationLoggingService::class.java)
        stopService(intent)
        updateStatusText("Service stopped")
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            list.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        permissionLauncher.launch(list.toTypedArray())
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
            .show()
    }

    private fun showUpdateApiKeyDialog() {
        val editText = EditText(this).apply {
            hint = "Enter API Key"
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val currentKey = db.settingDao().getSetting("api_key")?.value ?: ""
            withContext(Dispatchers.Main) {
                editText.setText(currentKey)
                editText.setSelection(editText.text.length)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update API Key")
                    .setMessage("Enter the API key for server synchronization:")
                    .setView(editText)
                    .setPositiveButton("Save") { _, _ ->
                        val newApiKey = editText.text.toString().trim()
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.settingDao().insert(Setting("api_key", newApiKey))
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "API Key updated", Toast.LENGTH_SHORT).show()
                                refreshApiKeyStatus()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun isAppLaunchedByUser(): Boolean {
        return (callingActivity != null || intent?.action == Intent.ACTION_MAIN)
    }
}