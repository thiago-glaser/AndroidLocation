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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.content.DialogInterface
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: LocationDatabase
    private lateinit var apiClient: ApiClient
    private var activeSession: SessionItem? = null
    private var isSessionActionInProgress = false
    private var cachedCars: List<CarItem>? = null

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Tip: Set Location permission to 'Allow all the time' for uninterrupted background logging.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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
            updateStatusText(getString(R.string.main_permissions_granted_starting))
            startLocationService()
            checkBackgroundLocationPermission()
        } else {
            updateStatusText(getString(R.string.main_permissions_missing))
            Toast.makeText(this, getString(R.string.main_permissions_must_grant), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = LocationDatabase.getDatabase(this)
        apiClient = ApiClient(this)

        checkLoginState()
        checkAndRequestPermissionsWithDisclosure()

        if (!isAppLaunchedByUser()) {
            updateStatusText(getString(R.string.main_warning_open_manually))
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        setupFeatureNavigation()
        setupSettingsControls()
                        refreshApiKeyStatus()
                        refreshActiveSessionState()
    }

    override fun onResume() {
        super.onResume()
        try {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notificationManager?.cancel(PackageReplacedReceiver.NOTIFICATION_ID_RESUME)
        } catch (_: Exception) {}
        refreshActiveSessionState()
        preloadCars()
    }

    private fun preloadCars() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                apiClient.getCars().onSuccess {
                    cachedCars = it
                }
            } catch (_: Exception) {}
        }
    }

    private fun refreshActiveSessionState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val latestEvent = try {
                db.sessionEventDao().getLatestEvent()
            } catch (_: Exception) { null }

            val result = apiClient.getLatestSession()
            withContext(Dispatchers.Main) {
                // If an action (start/end) is in flight locally, preserve the optimistic UI
                if (isSessionActionInProgress) return@withContext

                val isLocallyEnded = latestEvent != null && latestEvent.eventType == "end" && !latestEvent.wasSent
                val isLocallyStarted = latestEvent != null && latestEvent.eventType == "start" && !latestEvent.wasSent

                if (isLocallyEnded) {
                    activeSession = null
                    binding.tvActiveSessionStatus.visibility = View.GONE
                    binding.btnStartEndSession.text = getString(R.string.main_btn_start_session)
                    binding.btnStartEndSession.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                } else if (isLocallyStarted) {
                    val serverActive = result.getOrNull()?.takeIf { it.endTime.isNullOrEmpty() }
                    if (serverActive != null) {
                        activeSession = serverActive
                    } else if (activeSession == null || !activeSession?.endTime.isNullOrEmpty()) {
                        activeSession = SessionItem(
                            id = "",
                            deviceId = latestEvent.deviceId ?: LocationLoggingService.getDeviceAndroidId(this@MainActivity),
                            carId = latestEvent.carId,
                            startTime = latestEvent.timestampUtc,
                            endTime = null,
                            type = "P",
                            distance = null,
                            cost = null,
                            description = null,
                            locationStart = null,
                            locationEnd = null
                        )
                    }
                    val carLabel = activeSession?.description?.takeIf { it.isNotBlank() } ?: getString(R.string.common_vehicle)
                    binding.tvActiveSessionStatus.text = getString(R.string.main_session_active_label, carLabel)
                    binding.tvActiveSessionStatus.visibility = View.VISIBLE
                    binding.btnStartEndSession.text = getString(R.string.main_btn_end_session)
                    binding.btnStartEndSession.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
                } else {
                    val serverSession = result.getOrNull()
                    if (serverSession != null && serverSession.endTime.isNullOrEmpty()) {
                        activeSession = serverSession
                        val carLabel = serverSession.description?.takeIf { it.isNotBlank() } ?: getString(R.string.common_vehicle)
                        binding.tvActiveSessionStatus.text = getString(R.string.main_session_active_label, carLabel)
                        binding.tvActiveSessionStatus.visibility = View.VISIBLE
                        binding.btnStartEndSession.text = getString(R.string.main_btn_end_session)
                        binding.btnStartEndSession.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
                    } else {
                        activeSession = null
                        binding.tvActiveSessionStatus.visibility = View.GONE
                        binding.btnStartEndSession.text = getString(R.string.main_btn_start_session)
                        binding.btnStartEndSession.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                    }
                }
            }
        }
    }

    private fun handleStartEndSessionClick() {
        if (isSessionActionInProgress) {
            AppLogger.d("MainActivity", "handleStartEndSessionClick: Ignored because another action is in progress")
            return
        }

        val currentActive = activeSession
        if (currentActive != null && currentActive.endTime.isNullOrEmpty()) {
            endActiveSession(currentActive)
        } else {
            startNewSessionFlow()
        }
    }

    private fun endActiveSession(currentActive: SessionItem) {
        isSessionActionInProgress = true
        binding.btnStartEndSession.isEnabled = false

        // Immediate Optimistic UI: change to green "Start Session"
        activeSession = null
        binding.tvActiveSessionStatus.visibility = View.GONE
        binding.btnStartEndSession.text = getString(R.string.main_btn_start_session)
        binding.btnStartEndSession.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val utcNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                val event = SessionEvent(
                    deviceId = currentActive.deviceId?.ifBlank { LocationLoggingService.getDeviceAndroidId(this@MainActivity) }
                        ?: LocationLoggingService.getDeviceAndroidId(this@MainActivity),
                    sessionId = currentActive.id.takeIf { it.isNotBlank() },
                    carId = currentActive.carId,
                    eventType = "end",
                    timestampUtc = utcNow
                )
                db.sessionEventDao().insert(event)
                LocationLoggingService.notifySessionStateChanged(this@MainActivity)
                LocationLoggingService.triggerFlush(this@MainActivity)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.main_session_ended_toast), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "Failed to end session", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isSessionActionInProgress = false
                    binding.btnStartEndSession.isEnabled = true
                    refreshActiveSessionState()
                }
            }
        }
    }

    private fun startNewSessionFlow() {
        val cars = cachedCars
        if (cars != null) {
            handleCarsLoadedForStart(cars)
            return
        }

        isSessionActionInProgress = true
        binding.btnStartEndSession.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val carsRes = apiClient.getCars()
            withContext(Dispatchers.Main) {
                isSessionActionInProgress = false
                binding.btnStartEndSession.isEnabled = true
                carsRes.onSuccess { list ->
                    cachedCars = list
                    handleCarsLoadedForStart(list)
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, getString(R.string.common_failed, err.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleCarsLoadedForStart(cars: List<CarItem>) {
        when {
            cars.isEmpty() -> {
                Toast.makeText(this, getString(R.string.trips_register_car_first), Toast.LENGTH_LONG).show()
                startActivity(Intent(this, CarsActivity::class.java))
            }
            cars.size == 1 -> {
                startManualSessionForCar(cars[0])
            }
            else -> {
                val labels = cars.map { car ->
                    val desc = car.description?.takeIf { it.isNotBlank() }
                    val plate = car.licensePlate?.takeIf { it.isNotBlank() }
                    when {
                        desc != null && plate != null -> "$desc ($plate)"
                        desc != null -> desc
                        plate != null -> plate
                        else -> "Car #${car.id}"
                    }
                }.toTypedArray()

                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.main_select_car_title)
                    .setItems(labels) { dialog, which ->
                        dialog.dismiss()
                        startManualSessionForCar(cars[which])
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }
    }

    private fun startManualSessionForCar(car: CarItem) {
        if (hasRequiredPermissions()) {
            startLocationService()
        }

        isSessionActionInProgress = true
        binding.btnStartEndSession.isEnabled = false

        val carLabel = car.description?.takeIf { it.isNotBlank() } ?: car.licensePlate?.takeIf { it.isNotBlank() } ?: "Car #${car.id}"
        val utcNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        // Immediate Optimistic UI: change to red "End Session"
        activeSession = SessionItem(
            id = "",
            deviceId = LocationLoggingService.getDeviceAndroidId(this@MainActivity),
            carId = car.id,
            startTime = utcNow,
            endTime = null,
            type = "P",
            distance = null,
            cost = null,
            description = carLabel,
            locationStart = null,
            locationEnd = null
        )
        binding.tvActiveSessionStatus.text = getString(R.string.main_session_active_label, carLabel)
        binding.tvActiveSessionStatus.visibility = View.VISIBLE
        binding.btnStartEndSession.text = getString(R.string.main_btn_end_session)
        binding.btnStartEndSession.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val event = SessionEvent(
                    deviceId = LocationLoggingService.getDeviceAndroidId(this@MainActivity),
                    carId = car.id,
                    eventType = "start",
                    timestampUtc = utcNow
                )
                db.sessionEventDao().insert(event)
                LocationLoggingService.notifySessionStateChanged(this@MainActivity)
                LocationLoggingService.triggerFlush(this@MainActivity)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.main_session_started_toast, carLabel), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "Failed to start session", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isSessionActionInProgress = false
                    binding.btnStartEndSession.isEnabled = true
                    refreshActiveSessionState()
                }
            }
        }
    }

    private fun setupFeatureNavigation() {
        // Native feature screen launches
        binding.btnStartEndSession.setOnClickListener {
            handleStartEndSessionClick()
        }
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

        binding.btnNavSubscription.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        // Main page button to open Settings
        binding.btnToggleSettings.setOnClickListener {
            binding.layoutMain.visibility = View.GONE
            binding.settingsLayout.visibility = View.VISIBLE
                            refreshApiKeyStatus()
                        refreshActiveSessionState()
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
        binding.btnStart.setOnClickListener { checkAndRequestPermissionsWithDisclosure() }
        binding.btnStop.setOnClickListener { stopLocationService() }
        binding.btnSettingsSubscription.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }
        binding.btnUpdateApiKey.setOnClickListener { showUpdateApiKeyDialog() }
        binding.btnChangeTrackingMode.setOnClickListener { showChangeTrackingModeDialog() }
        binding.btnChangeUnits.setOnClickListener { showChangeUnitSystemDialog() }
        binding.btnChangeLanguage.setOnClickListener { showChangeLanguageDialog() }
        binding.btnOpenLogcat.setOnClickListener {
            startActivity(Intent(this, LogcatActivity::class.java))
        }
        binding.btnCleanLocationData.setOnClickListener { showCleanLocationDataDialog() }
        binding.btnSignOut.setOnClickListener { confirmSignOut() }
    }

    private fun updateStatusText(message: String) {
        binding.tvStatus.text = message
        binding.tvMainSubtitle.text = message
    }

    private fun refreshApiKeyStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = db.settingDao().getSetting("api_key")?.value?.takeIf { it.isNotEmpty() } ?: apiClient.ensureApiKey()
            val username = db.settingDao().getSetting("username")?.value
            var unitSys = db.settingDao().getSetting("unit_system")?.value?.uppercase() ?: "METRIC"
            var trackingMode = db.settingDao().getSetting("tracking_mode")?.value?.uppercase() ?: "SESSION_ONLY"
            if (!apiKey.isNullOrEmpty()) {
                apiClient.ensureDeviceRegistered()
                apiClient.getUserSettings().onSuccess { settingsData ->
                    settingsData.unitSystem?.let { unitSys = it.uppercase() }
                    settingsData.trackingMode?.let { trackingMode = it.uppercase() }
                }
            }
            val isAdmin = apiClient.checkIsAdmin()
            val currentAppLocales = AppCompatDelegate.getApplicationLocales()
            val langText = if (currentAppLocales.isEmpty) {
                getString(R.string.lang_system_default)
            } else {
                val tag = currentAppLocales.toLanguageTags()
                if (tag.startsWith("pt", ignoreCase = true)) {
                    getString(R.string.lang_portuguese_br)
                } else {
                    getString(R.string.lang_english)
                }
            }
            withContext(Dispatchers.Main) {
                binding.tvUnitSystem.text = if (unitSys == "IMPERIAL") getString(R.string.main_unit_imperial) else getString(R.string.main_unit_metric)
                binding.tvTrackingMode.text = if (trackingMode == "ALL_TIME") getString(R.string.main_tracking_all_time) else getString(R.string.main_tracking_session_only)
                binding.tvLanguage.text = langText
                binding.btnOpenLogcat.visibility = if (isAdmin) View.VISIBLE else View.GONE
            }
            withContext(Dispatchers.Main) {
                binding.tvAccountUser.text = if (!username.isNullOrEmpty()) username else if (!apiKey.isNullOrEmpty()) getString(R.string.main_account_configured_key) else getString(R.string.main_account_not_logged_in)
                if (!apiKey.isNullOrEmpty()) {
                    val masked = if (apiKey.length > 8) {
                        "${apiKey.take(4)}••••${apiKey.takeLast(4)}"
                    } else {
                        "••••••••"
                    }
                    binding.tvApiKeyStatus.text = getString(R.string.main_api_key_configured, masked)
                    binding.tvApiKeyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                } else {
                    binding.tvApiKeyStatus.text = getString(R.string.main_api_key_not_set)
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
            lifecycleScope.launch(Dispatchers.IO) {
                db.settingDao().insert(Setting("service_manually_stopped", "false"))
            }
            val intent = Intent(this, LocationLoggingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            LocationLoggingService.scheduleWatchdogAlarm(this)
            updateStatusText(getString(R.string.main_service_started_msg))
            requestIgnoreBatteryOptimizations()
        } else {
            Log.e("SERVICE", "Permission DENIED → Cannot start")
            updateStatusText(getString(R.string.main_service_error_location))
            showPermissionDeniedDialog()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val btScanGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

        val btConnectGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        val fgsLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.FOREGROUND_SERVICE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return fineGranted && btScanGranted && btConnectGranted && fgsLocationGranted
    }

    private fun checkAndRequestPermissionsWithDisclosure() {
        if (hasRequiredPermissions()) {
            startLocationService()
            checkBackgroundLocationPermission()
        } else {
            showLocationDisclosureDialog()
        }
    }

    private fun showLocationDisclosureDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_disclosure_title)
            .setMessage(R.string.dialog_disclosure_message)
            .setPositiveButton(R.string.dialog_disclosure_agree) { _, _ ->
                requestPermissions()
            }
            .setNegativeButton(R.string.dialog_disclosure_refuse) { _, _ ->
                updateStatusText(getString(R.string.dialog_disclosure_refuse_status))
            }
            .setCancelable(false)
            .show()
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBackground) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_bg_location_title)
                    .setMessage(R.string.dialog_bg_location_message)
                    .setPositiveButton(R.string.common_next) { _, _ ->
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun stopLocationService() {
        lifecycleScope.launch(Dispatchers.IO) {
            db.settingDao().insert(Setting("service_manually_stopped", "true"))
        }
        val intent = Intent(this, LocationLoggingService::class.java)
        stopService(intent)
        updateStatusText(getString(R.string.main_status_service_stopped))
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_battery_title)
                    .setMessage(R.string.dialog_battery_message)
                    .setPositiveButton(R.string.dialog_open_settings) { _, _ ->
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
                    .setNegativeButton(R.string.common_cancel, null)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            list.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        permissionLauncher.launch(list.toTypedArray())
    }

    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_perm_req_title)
            .setMessage(R.string.dialog_perm_req_message)
            .setPositiveButton(R.string.dialog_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showChangeLanguageDialog() {
        val options = arrayOf(
            getString(R.string.lang_system_default),
            getString(R.string.lang_english),
            getString(R.string.lang_portuguese_br)
        )
        val currentAppLocales = AppCompatDelegate.getApplicationLocales()
        val checkedItem = if (currentAppLocales.isEmpty) {
            0
        } else {
            val tag = currentAppLocales.toLanguageTags()
            if (tag.startsWith("pt", ignoreCase = true)) 2 else 1
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_select_language)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                dialog.dismiss()
                val targetLocales = when (which) {
                    1 -> LocaleListCompat.forLanguageTags("en")
                    2 -> LocaleListCompat.forLanguageTags("pt-BR")
                    else -> LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(targetLocales)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showCleanLocationDataDialog() {
        val options = arrayOf(
            getString(R.string.clean_loc_option_1m),
            getString(R.string.clean_loc_option_3m),
            getString(R.string.clean_loc_option_6m),
            getString(R.string.clean_loc_option_1y),
            getString(R.string.clean_loc_option_all)
        )
        val rangeKeys = arrayOf(
            "keep_1_month",
            "keep_3_months",
            "keep_6_months",
            "keep_1_year",
            "delete_all"
        )
        var selectedIndex = 1 // Default to Keep last three months

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clean_loc_title)
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_next) { _, _ ->
                val chosenOption = options[selectedIndex]
                val chosenKey = rangeKeys[selectedIndex]
                showConfirmCleanLocationDialog(chosenOption, chosenKey)
            }
            .show()
    }

    private fun showConfirmCleanLocationDialog(optionLabel: String, rangeKey: String) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clean_loc_confirm_title)
            .setMessage(getString(R.string.clean_loc_confirm_message, optionLabel))
            .setNegativeButton(R.string.clean_loc_btn_no) { d, _ ->
                d.dismiss()
            }
            .setPositiveButton(R.string.clean_loc_btn_yes) { _, _ ->
                executeCleanLocationData(optionLabel, rangeKey)
            }
            .create()

        dialog.setOnShowListener {
            // Emphasize 'No' as default focused action
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.apply {
                requestFocus()
                setTextColor(Color.parseColor("#4F46E5"))
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
                setTextColor(Color.parseColor("#DC2626"))
            }
        }

        dialog.show()
    }

    private fun executeCleanLocationData(optionLabel: String, rangeKey: String) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clean_loc_progress_title)
            .setMessage(R.string.clean_loc_progress_message)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = apiClient.deleteLocationData(rangeKey)
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                result.onSuccess { count ->
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.common_success)
                        .setMessage(getString(R.string.clean_loc_success_message, count))
                        .setPositiveButton(R.string.common_ok, null)
                        .show()
                }.onFailure { err ->
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.common_error)
                        .setMessage(err.message ?: getString(R.string.common_error))
                        .setPositiveButton(R.string.common_ok, null)
                        .show()
                }
            }
        }
    }

    private fun showChangeTrackingModeDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val currentMode = apiClient.getTrackingMode()
            val options = arrayOf(
                getString(R.string.main_tracking_session_only),
                getString(R.string.main_tracking_all_time)
            )
            val checkedItem = if (currentMode == "ALL_TIME") 1 else 0

            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.settings_select_tracking_mode)
                    .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                        val selectedMode = if (which == 1) "ALL_TIME" else "SESSION_ONLY"
                        dialog.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            apiClient.updateTrackingMode(selectedMode)
                            LocationLoggingService.notifySessionStateChanged(this@MainActivity)
                            withContext(Dispatchers.Main) {
                                binding.tvTrackingMode.text = if (selectedMode == "ALL_TIME") {
                                    getString(R.string.main_tracking_all_time)
                                } else {
                                    getString(R.string.main_tracking_session_only)
                                }
                                val modeLabel = if (selectedMode == "ALL_TIME") {
                                    getString(R.string.main_tracking_all_time)
                                } else {
                                    getString(R.string.main_tracking_session_only)
                                }
                                Toast.makeText(this@MainActivity, getString(R.string.settings_tracking_mode_set_toast, modeLabel), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }
    }

    private fun showChangeUnitSystemDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val currentUnit = apiClient.getUnitSystem()
            val options = arrayOf(getString(R.string.main_unit_metric), getString(R.string.main_unit_imperial))
            val checkedItem = if (currentUnit == "IMPERIAL") 1 else 0

            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.settings_select_unit)
                    .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                        val selectedUnit = if (which == 1) "IMPERIAL" else "METRIC"
                        dialog.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            apiClient.updateUserSettings(selectedUnit)
                            withContext(Dispatchers.Main) {
                                binding.tvUnitSystem.text = if (selectedUnit == "IMPERIAL") "Imperial (mi, gal, MPG)" else "Metric (km, L, km/L)"
                                Toast.makeText(this@MainActivity, getString(R.string.settings_unit_set_toast, selectedUnit), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }
    }

    private fun showUpdateApiKeyDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.settings_api_key_hint)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val currentKey = db.settingDao().getSetting("api_key")?.value ?: ""
            withContext(Dispatchers.Main) {
                editText.setText(currentKey)
                editText.setSelection(editText.text.length)

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.settings_update_api_key_title)
                    .setMessage(R.string.settings_update_api_key_msg)
                    .setView(editText)
                    .setPositiveButton(R.string.common_save) { _, _ ->
                        val newApiKey = editText.text.toString().trim()
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.settingDao().insert(Setting("api_key", newApiKey))
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, getString(R.string.settings_api_key_updated), Toast.LENGTH_SHORT).show()
                                                refreshApiKeyStatus()
                        refreshActiveSessionState()
                            }
                        }
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }
    }

    private fun checkLoginState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = db.settingDao().getSetting("api_key")?.value?.takeIf { it.isNotEmpty() } ?: apiClient.ensureApiKey()
            val authToken = db.settingDao().getSetting("auth_token")?.value
            if (apiKey.isNullOrEmpty() && authToken.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    val intent = Intent(this@MainActivity, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_logout_title)
            .setMessage(R.string.settings_logout_message)
            .setPositiveButton(R.string.main_btn_logout) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.settingDao().insert(Setting("api_key", ""))
                    db.settingDao().insert(Setting("auth_token", ""))
                    db.settingDao().insert(Setting("username", ""))
                    db.settingDao().insert(Setting("is_admin", "false"))
                    db.settingDao().insert(Setting("service_manually_stopped", "true"))
                    withContext(Dispatchers.Main) {
                        stopLocationService()
                        val intent = Intent(this@MainActivity, LoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun isAppLaunchedByUser(): Boolean {
        return (callingActivity != null || intent?.action == Intent.ACTION_MAIN)
    }
}
