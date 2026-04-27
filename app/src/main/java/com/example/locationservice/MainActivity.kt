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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.locationservice.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: LocationDatabase
    private lateinit var sessionAdapter: SessionAdapter
    private val client = OkHttpClient()
    private val gson = Gson()
    private var currentPage = 1
    private var totalPages = 1
    private var carList = listOf<Car>()

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
        requestPermissions()
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
        binding.btnUpdateApiKey.text = "Logout"
        binding.btnUpdateApiKey.setOnClickListener { logout() }

        setupRecyclerView()

        binding.swipeRefresh.setOnRefreshListener {
            fetchSessions()
        }

        binding.btnToggleSettings.setOnClickListener {
            binding.settingsLayout.visibility = View.VISIBLE
            binding.btnToggleSettings.visibility = View.GONE
            binding.btnReports.visibility = View.GONE
            binding.btnToggleFilters.visibility = View.GONE
        }

        binding.btnHideSettings.setOnClickListener {
            binding.settingsLayout.visibility = View.GONE
            binding.btnToggleSettings.visibility = View.VISIBLE
            binding.btnReports.visibility = View.VISIBLE
            binding.btnToggleFilters.visibility = View.VISIBLE
        }

        binding.btnToggleFilters.setOnClickListener {
            if (binding.layoutFilters.visibility == View.VISIBLE) {
                binding.layoutFilters.visibility = View.GONE
            } else {
                binding.layoutFilters.visibility = View.VISIBLE
            }
        }

        binding.btnReports.setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }

        binding.fabAddSession.setOnClickListener {
            showAddSessionDialog()
        }

        setupFilters()
        fetchCarsAndSessions()
    }

    private var isSpinnerSetup = false
    private fun setupFilters() {
        val years = arrayOf("Any Year", "2024", "2025", "2026")
        binding.spinnerYear.adapter = ArrayAdapter(this, R.layout.spinner_item, years)
        
        val months = arrayOf("Any Month", "Jan (1)", "Feb (2)", "Mar (3)", "Apr (4)", "May (5)", "Jun (6)", "Jul (7)", "Aug (8)", "Sep (9)", "Oct (10)", "Nov (11)", "Dec (12)")
        binding.spinnerMonth.adapter = ArrayAdapter(this, R.layout.spinner_item, months)

        val limits = arrayOf("20 per page", "50 per page", "100 per page", "All")
        binding.spinnerLimit.adapter = ArrayAdapter(this, R.layout.spinner_item, limits)

        val itemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerSetup) {
                    currentPage = 1
                    fetchSessions()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerCar.onItemSelectedListener = itemSelectedListener
        binding.spinnerYear.onItemSelectedListener = itemSelectedListener
        binding.spinnerMonth.onItemSelectedListener = itemSelectedListener
        binding.spinnerLimit.onItemSelectedListener = itemSelectedListener

        binding.btnClearFilters.setOnClickListener {
            isSpinnerSetup = false
            binding.spinnerCar.setSelection(0)
            binding.spinnerYear.setSelection(0)
            binding.spinnerMonth.setSelection(0)
            binding.spinnerLimit.setSelection(0)
            isSpinnerSetup = true
            currentPage = 1
            fetchSessions()
        }

        binding.btnPrevPage.setOnClickListener {
            if (currentPage > 1) {
                currentPage--
                fetchSessions()
            }
        }

        binding.btnNextPage.setOnClickListener {
            if (currentPage < totalPages) {
                currentPage++
                fetchSessions()
            }
        }

        isSpinnerSetup = true
    }

    private fun setupRecyclerView() {
        sessionAdapter = SessionAdapter(
            onMapClick = { session ->
                val intent = Intent(this, MapActivity::class.java).apply {
                    putExtra("DEVICE_ID", session.deviceId)
                    putExtra("START_TIME", session.startTime)
                    putExtra("END_TIME", session.endTime)
                }
                startActivity(intent)
            },
            onTypeToggle = { session -> toggleSessionType(session) },
            onDelete = { session -> deleteSession(session) },
            onEnd = { session ->
                val isActive = session.endTime.isNullOrEmpty()
                if (isActive) {
                    endSession(session)
                }
            }
        )
        binding.rvSessions.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sessionAdapter
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun toggleSessionType(session: Session) {
        val newType = if (session.type == "B") "P" else "B"
        val payload = JSONObject().apply {
            put("id", session.id)
            put("type", newType)
        }.toString()

        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = db.settingDao().getSetting("api_key")?.value ?: return@launch
            val request = Request.Builder()
                .url("https://travel-access.ddns.net/api/sessions")
                .header("X-API-Key", apiKey)
                .patch(payload.toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    withContext(Dispatchers.Main) { fetchSessions() }
                }
            }
        }
    }

    private fun deleteSession(session: Session) {
        AlertDialog.Builder(this)
            .setTitle("Confirm Delete")
            .setMessage("Are you sure you want to delete this session?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val apiKey = db.settingDao().getSetting("api_key")?.value ?: return@launch
                    val request = Request.Builder()
                        .url("https://travel-access.ddns.net/api/sessions?id=${session.id}")
                        .header("X-API-Key", apiKey)
                        .delete()
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            withContext(Dispatchers.Main) { fetchSessions() }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun endSession(session: Session) {
        val utcNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val payload = JSONObject().apply {
            put("device_id", session.deviceId)
            put("timestamp_utc", utcNow)
            put("id", session.id)
        }.toString()

        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = db.settingDao().getSetting("api_key")?.value ?: return@launch
            val request = Request.Builder()
                .url("https://travel-access.ddns.net/api/Session/end-session")
                .header("X-API-Key", apiKey)
                .post(payload.toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    withContext(Dispatchers.Main) { fetchSessions() }
                }
            }
        }
    }

    private fun showAddSessionDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val deviceIdInput = EditText(this).apply {
            hint = "Device ID"
            setText(Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
        }

        val carSpinner = android.widget.Spinner(this).apply {
            val names = carList.map { it.description ?: it.licensePlate ?: "Car #${it.id}" }
            adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, names)
        }

        val typeSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, arrayOf("Personal", "Business"))
        }

        val utcNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val startInput = EditText(this).apply {
            hint = "Start Time (UTC ISO)"
            setText(utcNow)
        }

        val endInput = EditText(this).apply {
            hint = "End Time (UTC ISO) - Optional"
        }

        layout.addView(android.widget.TextView(this).apply { text = "Device ID"; setPadding(0,20,0,0) })
        layout.addView(deviceIdInput)
        layout.addView(android.widget.TextView(this).apply { text = "Car"; setPadding(0,20,0,0) })
        layout.addView(carSpinner)
        layout.addView(android.widget.TextView(this).apply { text = "Session Type"; setPadding(0,20,0,0) })
        layout.addView(typeSpinner)
        layout.addView(android.widget.TextView(this).apply { text = "Start Time (UTC)"; setPadding(0,20,0,0) })
        layout.addView(startInput)
        layout.addView(android.widget.TextView(this).apply { text = "End Time (UTC)"; setPadding(0,20,0,0) })
        layout.addView(endInput)

        AlertDialog.Builder(this)
            .setTitle("Add Session")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val deviceId = deviceIdInput.text.toString().trim()
                if (deviceId.isEmpty()) return@setPositiveButton
                
                val carIdx = carSpinner.selectedItemPosition
                if (carIdx < 0 || carIdx >= carList.size) return@setPositiveButton
                val selectedCarId = carList[carIdx].id
                
                val type = if (typeSpinner.selectedItemPosition == 0) "P" else "B"
                val start = startInput.text.toString().trim()
                val end = endInput.text.toString().trim()

                val payload = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("carId", selectedCarId)
                    put("startUtc", start)
                    put("sessionType", type)
                    if (end.isNotEmpty()) {
                        put("endUtc", end)
                    }
                }.toString()

                lifecycleScope.launch(Dispatchers.IO) {
                    val apiKey = db.settingDao().getSetting("api_key")?.value ?: return@launch
                    val request = Request.Builder()
                        .url("https://travel-access.ddns.net/api/sessions")
                        .header("X-API-Key", apiKey)
                        .post(payload.toRequestBody(JSON))
                        .build()

                    try {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                withContext(Dispatchers.Main) { fetchSessions() }
                            } else {
                                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Failed to add", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchCarsAndSessions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = db.settingDao().getSetting("api_key")?.value ?: ""
            if (apiKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
                return@launch
            }

            val request = Request.Builder()
                .url("https://travel-access.ddns.net/api/user/cars")
                .header("X-API-Key", apiKey)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        val carResponse = gson.fromJson(bodyStr, CarResponse::class.java)
                        if (carResponse.success) {
                            carList = carResponse.data ?: emptyList()
                            withContext(Dispatchers.Main) {
                                val carNames = mutableListOf("All Cars")
                                carList.forEach { car ->
                                    carNames.add(car.description ?: car.licensePlate ?: "Car #${car.id}")
                                }
                                val adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, carNames)
                                isSpinnerSetup = false
                                binding.spinnerCar.adapter = adapter
                                isSpinnerSetup = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore errors for cars and continue
            }

            // After trying to fetch cars, fetch sessions
            withContext(Dispatchers.Main) {
                fetchSessions()
            }
        }
    }

    private fun fetchSessions() {
        binding.swipeRefresh.isRefreshing = true

        val selectedCarIdx = binding.spinnerCar.selectedItemPosition
        val selectedYearIdx = binding.spinnerYear.selectedItemPosition
        val selectedMonthIdx = binding.spinnerMonth.selectedItemPosition
        val selectedLimitIdx = binding.spinnerLimit.selectedItemPosition

        val limitMap = arrayOf(20, 50, 100, 1000000)
        val limit = if (selectedLimitIdx >= 0 && selectedLimitIdx < limitMap.size) limitMap[selectedLimitIdx] else 20
        
        var url = "https://travel-access.ddns.net/api/sessions?page=$currentPage&limit=$limit"
        
        if (selectedCarIdx > 0 && selectedCarIdx <= carList.size) {
            val carId = carList[selectedCarIdx - 1].id
            url += "&carId=$carId"
        }
        
        val years = arrayOf("Any Year", "2024", "2025", "2026")
        if (selectedYearIdx > 0 && selectedYearIdx < years.size) {
            url += "&year=${years[selectedYearIdx]}"
        }
        if (selectedMonthIdx > 0) {
            url += "&month=$selectedMonthIdx"
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = db.settingDao().getSetting("api_key")?.value ?: ""
            if (apiKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    binding.swipeRefresh.isRefreshing = false
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
                return@launch
            }

            val request = Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        val sessionResponse = gson.fromJson(bodyStr, SessionResponse::class.java)
                        withContext(Dispatchers.Main) {
                            sessionAdapter.submitList(sessionResponse.data ?: emptyList())
                            binding.swipeRefresh.isRefreshing = false
                            
                            val pag = sessionResponse.pagination
                            if (pag != null) {
                                currentPage = pag.page
                                totalPages = pag.totalPages
                                binding.tvPageInfo.text = "Page $currentPage / $totalPages"
                                binding.btnPrevPage.isEnabled = currentPage > 1
                                binding.btnNextPage.isEnabled = currentPage < totalPages
                            } else {
                                binding.tvPageInfo.text = "Page 1 / 1"
                                binding.btnPrevPage.isEnabled = false
                                binding.btnNextPage.isEnabled = false
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.swipeRefresh.isRefreshing = false
                            Toast.makeText(this@MainActivity, "Error: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.swipeRefresh.isRefreshing = false
                    Toast.makeText(this@MainActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
            requestIgnoreBatteryOptimizations()
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

    private fun logout() {
        lifecycleScope.launch(Dispatchers.IO) {
            val setting = db.settingDao().getSetting("api_key")
            if (setting != null) {
                db.settingDao().delete(setting)
            }
            withContext(Dispatchers.Main) {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
            }
        }
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
                    fetchSessions()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}