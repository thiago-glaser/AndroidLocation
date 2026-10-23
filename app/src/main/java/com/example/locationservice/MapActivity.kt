package com.example.locationservice

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

data class GpsDataResponse(
    val success: Boolean,
    val data: List<LocationPoint>?
)

data class LocationPoint(
    val lat: Double,
    val lng: Double,
    val altitude: Double?,
    val date: String
)

// Esri World Street Map tile source (Free, open access, high performance, no 403 blocks)
val ESRI_WORLD_STREET_MAP: OnlineTileSourceBase = object : OnlineTileSourceBase(
    "EsriWorldStreetMap",
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
    }
}

class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var progressBar: ProgressBar
    private lateinit var db: LocationDatabase
    private val client = OkHttpClient()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup osmdroid config before loading shared preferences or inflating view
        val ctx = applicationContext
        val config = Configuration.getInstance()
        
        // OpenStreetMap blocks generic/default user-agents (com.example.*, osmdroid) with HTTP 403 Forbidden.
        // Provide a unique User-Agent and contact info per OSM Tile Usage Policy:
        // https://wiki.openstreetmap.org/wiki/Blocked_tiles
        config.userAgentValue = "TravelAccessLocationServices/1.0 (Android App; contact@travel-access.ddns.net)"
        config.additionalHttpRequestProperties["Referer"] = "https://travel-access.ddns.net/"
        config.load(ctx, ctx.getSharedPreferences("osmdroid", MODE_PRIVATE))
        
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)
        // Use Esri World Street Map tile source (free, open access, no 403 blocks)
        mapView.setTileSource(ESRI_WORLD_STREET_MAP)
        // Clear cached tiles from disk/memory to remove any stale or blocked tiles from previous runs
        mapView.tileProvider.clearTileCache()
        
        progressBar = findViewById(R.id.progressBar)
        db = LocationDatabase.getDatabase(this)

        mapView.setMultiTouchControls(true)

        val deviceId = intent.getStringExtra("DEVICE_ID")
        val startTime = intent.getStringExtra("START_TIME")
        var endTime = intent.getStringExtra("END_TIME")
        
        if (deviceId == null || startTime == null) {
            Toast.makeText(this, "Missing session data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (endTime.isNullOrEmpty()) {
            val d = java.util.Date()
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            endTime = format.format(d) + "Z"
        }

        fetchSessionRoute(deviceId, startTime, endTime)
    }

    private fun fetchSessionRoute(deviceId: String, startTime: String, endTime: String) {
        progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = db.settingDao().getSetting("api_key")?.value ?: ""
            if (apiKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MapActivity, "API Key missing", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            
            // Build URL with safe parameter encoding and alias parameter support
            val httpUrl = "https://travel-access.ddns.net/api/gps-data".toHttpUrl().newBuilder()
                .addQueryParameter("startDate", startTime)
                .addQueryParameter("endDate", endTime)
                .addQueryParameter("deviceId", deviceId)
                .addQueryParameter("start_date", startTime)
                .addQueryParameter("end_date", endTime)
                .addQueryParameter("device_id", deviceId)
                .addQueryParameter("startTime", startTime)
                .addQueryParameter("endTime", endTime)
                .build()

            val request = Request.Builder()
                .url(httpUrl)
                .header("X-API-Key", apiKey)
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", "LocationServicesApp/1.0")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val gpsData = gson.fromJson(bodyStr, GpsDataResponse::class.java)
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            drawRoute(gpsData.data ?: emptyList())
                        }
                    } else {
                        Log.e("MapActivity", "Error fetching GPS data ${response.code}: $bodyStr")
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            Toast.makeText(this@MapActivity, "Error: ${response.code} ${response.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MapActivity", "Network error fetching GPS data", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MapActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun drawRoute(locations: List<LocationPoint>) {
        if (locations.isEmpty()) {
            Toast.makeText(this, "No route data found", Toast.LENGTH_SHORT).show()
            return
        }

        val geoPoints = ArrayList<GeoPoint>()
        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0

        for (loc in locations) {
            val point = GeoPoint(loc.lat, loc.lng)
            geoPoints.add(point)
            
            if (loc.lat < minLat) minLat = loc.lat
            if (loc.lat > maxLat) maxLat = loc.lat
            if (loc.lng < minLng) minLng = loc.lng
            if (loc.lng > maxLng) maxLng = loc.lng
        }

        val polyline = Polyline()
        polyline.setPoints(geoPoints)
        polyline.outlinePaint.color = android.graphics.Color.BLUE
        polyline.outlinePaint.strokeWidth = 8f

        mapView.overlays.add(polyline)
        
        val startMarker = Marker(mapView)
        startMarker.position = geoPoints.first()
        startMarker.title = "Start"
        mapView.overlays.add(startMarker)
        
        val endMarker = Marker(mapView)
        endMarker.position = geoPoints.last()
        endMarker.title = "End"
        mapView.overlays.add(endMarker)

        val boundingBox = BoundingBox(maxLat, maxLng, minLat, minLng)
        mapView.post {
            // Expand bounds slightly so the line isn't touching the edge exactly
            mapView.zoomToBoundingBox(boundingBox, true, 100)
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}
