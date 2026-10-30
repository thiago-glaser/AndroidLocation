package com.example.locationservice.auto

import android.location.Location
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.ApiClient
import com.example.locationservice.FuelItem
import com.example.locationservice.LocationDatabase
import com.example.locationservice.LocationLoggingService
import com.example.locationservice.MapPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class AutoSessionMapScreen(carContext: CarContext) : Screen(carContext) {

    private val db = LocationDatabase.getDatabase(carContext)
    private val apiClient = ApiClient(carContext)
    private val surfaceRenderer = AutoMapSurfaceRenderer(carContext)

    private val autoLaunchUtc: String = formatUtcNow()
    private var observationJob: Job? = null
    private var telemetryJob: Job? = null
    private var fuelFetchJob: Job? = null

    @Volatile
    private var cachedPricePerKm: Double = 0.0

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                surfaceRenderer.register()
                fetchLatestFuelPricePerKm()
                startObservingLocalDb()
                startObservingLiveTelemetry()
            }

            override fun onStop(owner: LifecycleOwner) {
                observationJob?.cancel()
                observationJob = null
                telemetryJob?.cancel()
                telemetryJob = null
                fuelFetchJob?.cancel()
                fuelFetchJob = null
            }

            override fun onDestroy(owner: LifecycleOwner) {
                surfaceRenderer.unregister()
            }
        })
    }

    private fun fetchLatestFuelPricePerKm() {
        fuelFetchJob?.cancel()
        fuelFetchJob = lifecycleScope.launch(Dispatchers.IO) {
            // First check if cached in Setting
            val cachedSetting = db.settingDao().getSetting("last_calculated_price_per_km")?.value?.toDoubleOrNull()
            if (cachedSetting != null && cachedSetting > 0.0) {
                cachedPricePerKm = cachedSetting
            }

            // Also check latest active Bluetooth car if any
            val latestEvent = db.sessionEventDao().getLatestEvent()
            var activeCarId: String? = null
            if (latestEvent != null && latestEvent.eventType == "start") {
                val btDevice = db.bluetoothDeviceDao().getAll().find { it.address.equals(latestEvent.bluetoothAddress, ignoreCase = true) }
                if (btDevice != null && btDevice.carId.isNotEmpty()) {
                    activeCarId = btDevice.carId
                }
            }

            try {
                val fuelRes = apiClient.getFuel()
                fuelRes.onSuccess { fuelList ->
                    val resolvedRate = resolvePricePerKm(fuelList, activeCarId)
                    if (resolvedRate > 0.0) {
                        cachedPricePerKm = resolvedRate
                        db.settingDao().insert(com.example.locationservice.Setting("last_calculated_price_per_km", resolvedRate.toString()))
                    }
                }
            } catch (e: Exception) {
                Log.w("AutoSessionMapScreen", "Could not fetch fuel logs from cloud", e)
            }
        }
    }

    private fun resolvePricePerKm(fuelList: List<FuelItem>, carId: String?): Double {
        if (fuelList.isEmpty()) return 0.0

        // If a specific car is identified, filter by car first
        val carFiltered = if (!carId.isNullOrEmpty()) {
            fuelList.filter { it.carId == carId }
        } else {
            fuelList
        }

        val pool = if (carFiltered.isNotEmpty()) carFiltered else fuelList

        // Sort chronologically by date descending to find the last calculated log
        val sortedLogs = pool.sortedByDescending { log ->
            parseUtcMillis(log.timestampUtc) ?: 0L
        }

        // Find the most recent fuel log with a positive calculated pricePerKilometer
        val lastCalculated = sortedLogs.firstOrNull { (it.pricePerKilometer ?: 0.0) > 0.0 }
        if (lastCalculated != null) {
            return lastCalculated.pricePerKilometer ?: 0.0
        }

        // Fallback: if pricePerKilometer isn't computed yet but totalValue & totalKilometers exist
        val fallbackLog = sortedLogs.firstOrNull {
            (it.totalValue ?: 0.0) > 0.0 && (it.totalKilometers ?: 0.0) > 0.0
        }
        if (fallbackLog != null) {
            return fallbackLog.totalValue!! / fallbackLog.totalKilometers!!
        }

        return 0.0
    }

    private fun startObservingLiveTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = lifecycleScope.launch {
            LocationLoggingService.liveTelemetry.collect { telemetry ->
                if (telemetry != null) {
                    surfaceRenderer.updateLiveTelemetry(
                        speedKmh = telemetry.speedKmh,
                        altitudeMeters = telemetry.altitudeMeters
                    )
                }
            }
        }
    }

    private fun startObservingLocalDb() {
        observationJob?.cancel()
        observationJob = lifecycleScope.launch {
            db.sessionEventDao()
                .observeLatestEvent()
                .map { latestEvent ->
                    if (latestEvent != null && latestEvent.eventType == "start") {
                        Pair(latestEvent.timestampUtc, true)
                    } else {
                        Pair(autoLaunchUtc, false)
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { (sessionStartUtc, isBluetoothSession) ->
                    db.locationDao()
                        .observeLocationsSince(sessionStartUtc)
                        .map { locations ->
                            Triple(sessionStartUtc, isBluetoothSession, locations)
                        }
                }
                .flowOn(Dispatchers.IO)
                .collect { (sessionStartUtc, isBluetoothSession, queuedLocations) ->
                    val rawPoints = queuedLocations.map { loc ->
                        MapPoint(
                            lat = loc.latitude,
                            lng = loc.longitude,
                            altitude = loc.altitude,
                            date = loc.timestampUtc
                        )
                    }

                    val filteredPoints = filterPointsByDistance(rawPoints, 10.0)
                    val distanceMeters = calculateTotalDistanceMeters(filteredPoints)
                    val distanceKm = distanceMeters / 1000.0
                    val endTimestamp = rawPoints.lastOrNull()?.date ?: formatUtcNow()
                    val durationStr = calculateDurationString(sessionStartUtc, endTimestamp)

                    // Calculate average speed: distanceKm / durationHours
                    val durationHours = calculateDurationHours(sessionStartUtc, endTimestamp)
                    val avgSpeedKmh = if (durationHours > (1.0 / 360.0) && distanceKm > 0.05) {
                        (distanceKm / durationHours).coerceIn(0.0, 250.0)
                    } else 0.0

                    // Calculate trip cost using last calculated fuel log (value / km)
                    val currentPricePerKm = cachedPricePerKm
                    val tripCost = distanceKm * currentPricePerKm

                    val live = LocationLoggingService.liveTelemetry.value
                    val currentSpeedKmh = live?.speedKmh ?: computeFallbackSpeedKmh(rawPoints)
                    val currentAltitudeM = if (live != null && live.altitudeMeters != 0.0) {
                        live.altitudeMeters
                    } else {
                        rawPoints.lastOrNull { it.altitude != 0.0 }?.altitude ?: 0.0
                    }

                    val subtitle = buildSubtitle(
                        sessionStartUtc = sessionStartUtc,
                        isBluetoothSession = isBluetoothSession,
                        pointCount = filteredPoints.size
                    )

                    surfaceRenderer.updateSessionData(
                        filteredPoints = filteredPoints,
                        totalRawPoints = rawPoints.size,
                        distanceKm = distanceKm,
                        speedKmh = currentSpeedKmh,
                        avgSpeedKmh = avgSpeedKmh,
                        tripCost = tripCost,
                        pricePerKm = currentPricePerKm,
                        altitudeMeters = currentAltitudeM,
                        durationText = durationStr,
                        subtitle = subtitle
                    )
                }
        }
    }

    override fun onGetTemplate(): Template {
        return try {
            val actionStrip = ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("Fit Route")
                        .setOnClickListener {
                            surfaceRenderer.fitAllSessionPoints()
                            surfaceRenderer.renderFrame()
                        }
                        .build()
                )
                .build()

            val builder = NavigationTemplate.Builder()
                .setActionStrip(actionStrip)

            if (carContext.carAppApiLevel >= 2) {
                try {
                    val mapActionStrip = ActionStrip.Builder()
                        .addAction(Action.PAN)
                        .build()
                    builder.setMapActionStrip(mapActionStrip)
                    builder.setPanModeListener { _ -> }
                } catch (e: Exception) {
                    Log.w("AutoSessionMapScreen", "MapActionStrip not supported by host", e)
                }
            }

            builder.build()
        } catch (e: Exception) {
            Log.e("AutoSessionMapScreen", "Fallback template due to error", e)
            val fallbackStrip = ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("Fit Route")
                        .setOnClickListener {
                            surfaceRenderer.fitAllSessionPoints()
                            surfaceRenderer.renderFrame()
                        }
                        .build()
                )
                .build()
            NavigationTemplate.Builder()
                .setActionStrip(fallbackStrip)
                .build()
        }
    }

    private fun computeFallbackSpeedKmh(points: List<MapPoint>): Double {
        if (points.size < 2) return 0.0
        val p1 = points[points.size - 2]
        val p2 = points.last()
        val t1 = parseUtcMillis(p1.date) ?: return 0.0
        val t2 = parseUtcMillis(p2.date) ?: return 0.0

        // If latest point is older than 45 seconds, vehicle is not actively moving
        if (System.currentTimeMillis() - t2 > 45_000L) return 0.0

        val deltaSec = (t2 - t1) / 1000.0
        if (deltaSec <= 0.5 || deltaSec > 120.0) return 0.0

        val results = FloatArray(1)
        Location.distanceBetween(p1.lat, p1.lng, p2.lat, p2.lng, results)
        val distMeters = results[0].toDouble()
        if (distMeters < 5.0) return 0.0

        val kmh = (distMeters / deltaSec) * 3.6
        return if (kmh in 1.5..250.0) kmh else 0.0
    }

    private fun parseUtcMillis(isoUtc: String?): Long? {
        if (isoUtc.isNullOrEmpty()) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            fmt.parse(isoUtc)?.time
        } catch (e: Exception) {
            try {
                val clean = isoUtc.substringBefore("Z").substringBefore(".")
                val fmt2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                fmt2.parse(clean)?.time
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun buildSubtitle(
        sessionStartUtc: String,
        isBluetoothSession: Boolean,
        pointCount: Int
    ): String {
        val startFormatted = formatTimeShort(sessionStartUtc)
        val sessionLabel = if (isBluetoothSession) "Active Session" else "Live Session"
        return if (pointCount > 0) {
            "$sessionLabel • Started $startFormatted"
        } else {
            "Waiting for GPS • Started $startFormatted"
        }
    }

    private fun filterPointsByDistance(
        points: List<MapPoint>,
        minDistanceMeters: Double = 10.0
    ): List<MapPoint> {
        if (points.size <= 1) return points

        val filtered = mutableListOf<MapPoint>()
        filtered.add(points[0])

        val results = FloatArray(1)
        for (i in 1 until points.size) {
            val last = filtered.last()
            Location.distanceBetween(last.lat, last.lng, points[i].lat, points[i].lng, results)
            val dist = results[0].toDouble()

            if (dist >= minDistanceMeters) {
                filtered.add(points[i])
            }
        }

        if (filtered.last() != points.last()) {
            filtered.add(points.last())
        }

        return filtered
    }

    private fun calculateTotalDistanceMeters(points: List<MapPoint>): Double {
        if (points.size <= 1) return 0.0
        var total = 0.0
        val results = FloatArray(1)
        for (i in 1 until points.size) {
            Location.distanceBetween(
                points[i - 1].lat,
                points[i - 1].lng,
                points[i].lat,
                points[i].lng,
                results
            )
            total += results[0]
        }
        return total
    }

    private fun calculateDurationHours(startIso: String, endIso: String): Double {
        val t1 = parseUtcMillis(startIso) ?: return 0.0
        val t2 = parseUtcMillis(endIso) ?: return 0.0
        val diffMs = (t2 - t1).coerceAtLeast(0L)
        return diffMs / (1000.0 * 60.0 * 60.0)
    }

    private fun calculateDurationString(startIso: String, endIso: String): String {
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val cleanStart = startIso.substringBefore("Z").substringBefore(".")
            val cleanEnd = endIso.substringBefore("Z").substringBefore(".")
            val d1 = inFmt.parse(cleanStart)
            val d2 = inFmt.parse(cleanEnd)
            if (d1 != null && d2 != null) {
                val diffMin = Math.max(1L, (d2.time - d1.time) / (1000 * 60))
                if (diffMin >= 60) {
                    val hrs = diffMin / 60
                    val mins = diffMin % 60
                    "⏱️ ${hrs}h ${mins}m"
                } else {
                    "⏱️ ${diffMin} min"
                }
            } else {
                "⏱️ 0 min"
            }
        } catch (e: Exception) {
            "⏱️ 0 min"
        }
    }

    private fun formatTimeShort(isoUtc: String): String {
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            val clean = isoUtc.substringBefore("Z").substringBefore(".")
            val parsed = inFmt.parse(clean)
            if (parsed != null) outFmt.format(parsed) else isoUtc
        } catch (e: Exception) {
            isoUtc
        }
    }

    companion object {
        private fun formatUtcNow(): String {
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
        }
    }
}
