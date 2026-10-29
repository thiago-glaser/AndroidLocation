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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class AutoSessionMapScreen(carContext: CarContext) : Screen(carContext) {

    private val db = LocationDatabase.getDatabase(carContext)
    private val surfaceRenderer = AutoMapSurfaceRenderer(carContext)

    private val autoLaunchUtc: String = formatUtcNow()
    private var observationJob: Job? = null
    private var telemetryJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                surfaceRenderer.register()
                startObservingLocalDb()
                startObservingLiveTelemetry()
            }

            override fun onStop(owner: LifecycleOwner) {
                observationJob?.cancel()
                observationJob = null
                telemetryJob?.cancel()
                telemetryJob = null
            }

            override fun onDestroy(owner: LifecycleOwner) {
                surfaceRenderer.unregister()
            }
        })
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
