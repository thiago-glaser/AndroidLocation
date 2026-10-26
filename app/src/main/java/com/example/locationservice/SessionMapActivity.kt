package com.example.locationservice

import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivitySessionMapBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MapPoint(
    val lat: Double,
    val lng: Double,
    val altitude: Double,
    val date: String?
)

class SessionMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionMapBinding
    private lateinit var db: LocationDatabase
    private lateinit var apiClient: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = LocationDatabase.getDatabase(this)
        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }

        setupWebView()

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
            binding.tvEmptyState.text = "Invalid session time range."
        }
    }

    private fun setupWebView() {
        binding.webViewMap.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "LocationServicesApp/1.0 (Android; TravelAccess)"
        }
    }

    private fun formatSubtitle(startTime: String, endTime: String?): String {
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outDateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val outTimeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

            val cleanStart = startTime.substringBefore("Z").substringBefore(".")
            val startDate = inFmt.parse(cleanStart)

            if (startDate != null) {
                val dateStr = outDateFmt.format(startDate)
                val startTimeStr = outTimeFmt.format(startDate)

                if (!endTime.isNullOrEmpty()) {
                    val cleanEnd = endTime.substringBefore("Z").substringBefore(".")
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
                binding.chipSource.text = if (isLocalSource) "Local DB" else "Cloud Sync"
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

            binding.chipDistance.text = String.format(Locale.US, "🛣️ %.2f km", distanceKm)
            binding.chipPoints.text = "📍 ${filteredPoints.size} pts"
            binding.chipDuration.text = calculateDurationString(startTime, effectiveEndTime)
            binding.chipSource.apply {
                text = if (isLocalSource) "Local DB" else "Cloud Sync"
                setTextColor(if (isLocalSource) Color.parseColor("#059669") else Color.parseColor("#0284C7"))
            }

            // 4. Render OpenStreetMap with Leaflet
            renderOpenStreetMap(filteredPoints)
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

    private fun renderOpenStreetMap(points: List<MapPoint>) {
        val jsonArray = JSONArray()
        points.forEach { p ->
            jsonArray.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
                put("altitude", p.altitude)
                put("date", p.date ?: "")
            })
        }

        val htmlContent = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>
        html, body, #map {
            width: 100%;
            height: 100%;
            margin: 0;
            padding: 0;
        }
        .leaflet-popup-content-wrapper {
            border-radius: 8px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            font-size: 13px;
        }
    </style>
</head>
<body>
    <div id="map"></div>
    <script>
        var map = L.map('map', {
            zoomControl: true,
            attributionControl: true
        });

        // OpenStreetMap Layer (OpenStreetMap France - free, open access, no API key, no 418)
        var osmLayer = L.tileLayer('https://{s}.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png', {
            maxZoom: 19,
            subdomains: 'abc',
            attribution: '&copy; OpenStreetMap France | &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        }).addTo(map);

        // Esri World Street Map (from project commit 7d44de1 - open access, no API key)
        var esriLayer = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}', {
            maxZoom: 19,
            attribution: 'Tiles &copy; Esri &mdash; Source: Esri, DeLorme, NAVTEQ, USGS, TomTom'
        });

        var baseMaps = {
            "OpenStreetMap": osmLayer,
            "Esri Streets": esriLayer
        };
        L.control.layers(baseMaps).addTo(map);

        var points = $jsonArray;

        if (points && points.length > 0) {
            var latlngs = points.map(function(p) { return [p.lat, p.lng]; });

            // Draw route polyline
            var polyline = L.polyline(latlngs, {
                color: '#2563EB',
                weight: 5,
                opacity: 0.85,
                lineJoin: 'round'
            }).addTo(map);

            // Add markers
            points.forEach(function(p, i) {
                var isStart = (i === 0);
                var isEnd = (i === points.length - 1);

                if (isStart) {
                    L.circleMarker([p.lat, p.lng], {
                        radius: 8,
                        fillColor: '#10B981',
                        color: '#FFFFFF',
                        weight: 2,
                        fillOpacity: 1
                    }).addTo(map).bindPopup('<b>🟢 Trip Start</b><br>' + (p.date || ''));
                } else if (isEnd) {
                    L.circleMarker([p.lat, p.lng], {
                        radius: 8,
                        fillColor: '#EF4444',
                        color: '#FFFFFF',
                        weight: 2,
                        fillOpacity: 1
                    }).addTo(map).bindPopup('<b>🔴 Trip End</b><br>' + (p.date || ''));
                } else {
                    L.circleMarker([p.lat, p.lng], {
                        radius: 4,
                        fillColor: '#3B82F6',
                        color: '#FFFFFF',
                        weight: 1,
                        fillOpacity: 0.9
                    }).addTo(map).bindPopup('<b>Point #' + (i + 1) + '</b><br>Time: ' + (p.date || '') + (p.altitude ? '<br>Alt: ' + Math.round(p.altitude) + 'm' : ''));
                }
            });

            // Center and zoom to fit entire route
            map.fitBounds(polyline.getBounds(), { padding: [40, 40] });
        }
    </script>
</body>
</html>
        """.trimIndent()

        binding.webViewMap.loadDataWithBaseURL("https://osm.org", htmlContent, "text/html", "UTF-8", null)
    }
}
