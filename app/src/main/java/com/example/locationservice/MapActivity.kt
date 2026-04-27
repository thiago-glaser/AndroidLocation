package com.example.locationservice

import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
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

class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var progressBar: ProgressBar
    private lateinit var db: LocationDatabase
    private val client = OkHttpClient()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup osmdroid config
        val ctx = applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        Configuration.getInstance().userAgentValue = packageName
        
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)
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
            
            val url = "https://travel-access.ddns.net/api/gps-data?startDate=$startTime&endDate=$endTime&deviceId=$deviceId"
            
            val request = Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        val gpsData = gson.fromJson(bodyStr, GpsDataResponse::class.java)
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            drawRoute(gpsData.data ?: emptyList())
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            Toast.makeText(this@MapActivity, "Error: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
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
        polyline.color = android.graphics.Color.BLUE
        polyline.width = 8f

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
