package com.example.locationservice

import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivitySessionMapBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

data class MapPoint(
    val lat: Double,
    val lng: Double,
    val altitude: Double,
    val date: String?
)

class SessionMapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivitySessionMapBinding
    private lateinit var db: LocationDatabase
    private lateinit var apiClient: ApiClient

    private var googleMap: GoogleMap? = null
    private var pendingPoints: List<MapPoint>? = null
    private var pendingIsImperial: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = LocationDatabase.getDatabase(this)
        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        val carTitle = intent.getStringExtra("CAR_TITLE") ?: "Trip Route"
        val startTime = intent.getStringExtra("START_TIME") ?: ""
        val endTime = intent.getStringExtra("END_TIME")
        val deviceId = intent.getStringExtra("DEVICE_ID")
        val sessionDistance = intent.getDoubleExtra("DISTANCE", 0.0)

        binding.tvMapTitle.text = carTitle
        binding.tvMapSubtitle.text = formatSubtitle(startTime, endTime)

        if (startTime.isNotEmpty()) {
            loadSessionPoints(startTime, endTime, deviceId, sessionDistance)
        } else {
            binding.progressBar.visibility = View.GONE
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = getString(R.string.trips_invalid_range)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMapToolbarEnabled = true
        }

        val pts = pendingPoints
        if (pts != null) {
            renderGoogleMap(pts, pendingIsImperial)
        }
    }

    private fun formatSubtitle(startTime: String, endTime: String?): String {
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outDateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val outTimeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

            val cleanStart = startTime.trim().replace(" ", "T").substringBefore("Z").substringBefore(".")
            val startDate = inFmt.parse(cleanStart)

            if (startDate != null) {
                val dateStr = outDateFmt.format(startDate)
                val startTimeStr = outTimeFmt.format(startDate)

                if (!endTime.isNullOrEmpty()) {
                    val cleanEnd = endTime.trim().replace(" ", "T").substringBefore("Z").substringBefore(".")
                    val endDate = inFmt.parse(cleanEnd)
                    val endTimeStr = if (endDate != null) outTimeFmt.format(endDate) else ""
                    "$dateStr • $startTimeStr - $endTimeStr"
                } else {
                    "$dateStr • Started at $startTimeStr (Active)"
                }
            } else {
                startTime
            }
        } catch (e: Exception) {
            startTime
        }
    }

    private fun formatMarkerTimestamp(rawDate: String?): String {
        if (rawDate.isNullOrBlank()) return ""
        return try {
            val cleanStr = rawDate.trim().replace(" ", "T").substringBefore("Z")
            val normalized = if (cleanStr.contains(".")) {
                val base = cleanStr.substringBefore(".")
                val millis = cleanStr.substringAfter(".").take(3).padEnd(3, '0')
                "$base.$millis"
            } else {
                cleanStr
            }

            val inFmt = SimpleDateFormat(if (normalized.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSS" else "yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = inFmt.parse(normalized)
            if (date != null) {
                val outFmt = SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.getDefault())
                outFmt.format(date)
            } else {
                rawDate
            }
        } catch (e: Exception) {
            rawDate
        }
    }

    private fun loadSessionPoints(
        startTime: String,
        endTime: String?,
        deviceId: String?,
        fallbackDistanceKm: Double
    ) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE

        val effectiveEndTime = if (!endTime.isNullOrEmpty()) {
            endTime
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
        }

        lifecycleScope.launch {
            // 1. Try Local SQLite Room Database first
            val localPoints = withContext(Dispatchers.IO) {
                try {
                    db.locationDao().getLocationsBetween(startTime, effectiveEndTime)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val rawPoints = mutableListOf<MapPoint>()
            var isLocalSource = false

            if (localPoints.isNotEmpty()) {
                isLocalSource = true
                localPoints.forEach { loc ->
                    rawPoints.add(MapPoint(loc.latitude, loc.longitude, loc.altitude, loc.timestampUtc))
                }
            } else {
                // 2. Fallback to Cloud API (/api/gps-data)
                val cloudRes = apiClient.getGpsData(startTime, effectiveEndTime, deviceId)
                cloudRes.onSuccess { list ->
                    list.forEach { p ->
                        rawPoints.add(MapPoint(p.lat, p.lng, p.altitude ?: 0.0, p.date))
                    }
                }.onFailure { err ->
                    Toast.makeText(this@SessionMapActivity, "Cloud fetch: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }

            binding.progressBar.visibility = View.GONE

            if (rawPoints.isEmpty()) {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.chipSource.text = if (isLocalSource) getString(R.string.trips_local_db) else getString(R.string.trips_cloud_sync)
                return@launch
            }

            // 3. Filter points with < 10 meters apart
            val filteredPoints = filterPointsByDistance(rawPoints, 10.0)

            // Update stats chips
            val calculatedDistanceMeters = calculateTotalDistanceMeters(filteredPoints)
            val distanceKm = if (calculatedDistanceMeters > 0) {
                calculatedDistanceMeters / 1000.0
            } else {
                fallbackDistanceKm
            }

            val isImperial = apiClient.isImperialUnits()
            binding.chipDistance.text = "🛣️ ${UnitConverter.formatDistance(distanceKm, isImperial)}"
            binding.chipPoints.text = "📍 ${filteredPoints.size} pts"
            binding.chipDuration.text = calculateDurationString(startTime, effectiveEndTime)
            binding.chipSource.apply {
                text = if (isLocalSource) getString(R.string.trips_local_db) else getString(R.string.trips_cloud_sync)
                setTextColor(if (isLocalSource) Color.parseColor("#059669") else Color.parseColor("#0284C7"))
            }

            // 4. Render native Google Map
            pendingPoints = filteredPoints
            pendingIsImperial = isImperial
            renderGoogleMap(filteredPoints, isImperial)
        }
    }

    /**
     * Filters out consecutive points closer than 10 meters using geodesic distance.
     */
    private fun filterPointsByDistance(points: List<MapPoint>, minDistanceMeters: Double = 10.0): List<MapPoint> {
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

        // Guarantee final point is kept
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
            Location.distanceBetween(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng, results)
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
                "⏱️ --"
            }
        } catch (e: Exception) {
            "⏱️ --"
        }
    }

    private fun renderGoogleMap(points: List<MapPoint>, isImperial: Boolean = false) {
        val map = googleMap ?: return
        map.clear()

        if (points.isEmpty()) return

        val latLngList = mutableListOf<LatLng>()
        val boundsBuilder = LatLngBounds.Builder()

        val altUnit = if (isImperial) "ft" else "m"
        val altFactor = if (isImperial) 3.28084 else 1.0

        points.forEachIndexed { index, p ->
            val pos = LatLng(p.lat, p.lng)
            latLngList.add(pos)
            boundsBuilder.include(pos)

            val isStart = index == 0
            val isEnd = index == points.lastIndex && points.size > 1

            val formattedDate = formatMarkerTimestamp(p.date)

            if (isStart) {
                map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .title("🟢 Trip Start")
                        .snippet(formattedDate)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )
            } else if (isEnd) {
                map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .title("🔴 Trip End")
                        .snippet(formattedDate)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
            } else {
                val altText = if (p.altitude != 0.0) {
                    " • Alt: ${(p.altitude * altFactor).roundToInt()}$altUnit"
                } else {
                    ""
                }
                map.addCircle(
                    CircleOptions()
                        .center(pos)
                        .radius(6.0)
                        .fillColor(Color.parseColor("#3B82F6"))
                        .strokeColor(Color.WHITE)
                        .strokeWidth(2f)
                        .clickable(false)
                )
                // Add an invisible/small info marker for every 10th intermediate point so route remains fast
                if (points.size <= 100 || index % 10 == 0) {
                    map.addMarker(
                        MarkerOptions()
                            .position(pos)
                            .title("Point #${index + 1}")
                            .snippet("$formattedDate$altText")
                            .alpha(0.01f)
                    )
                }
            }
        }

        if (latLngList.size > 1) {
            map.addPolyline(
                PolylineOptions()
                    .addAll(latLngList)
                    .color(Color.parseColor("#2563EB"))
                    .width(12f)
                    .jointType(JointType.ROUND)
                    .geodesic(true)
            )

            val bounds = boundsBuilder.build()
            binding.mapFragment.post {
                try {
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                } catch (_: Exception) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngList.first(), 14f))
                }
            }
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngList.first(), 15f))
        }
    }
}
