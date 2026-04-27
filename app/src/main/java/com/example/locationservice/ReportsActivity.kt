package com.example.locationservice

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ReportsActivity : AppCompatActivity() {

    private lateinit var tvTotalDistance: TextView
    private lateinit var tvTotalCost: TextView
    private lateinit var tvPersonalDistance: TextView
    private lateinit var tvBusinessDistance: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var rvReports: RecyclerView
    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var db: LocationDatabase

    private val client = OkHttpClient()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)

        tvTotalDistance = findViewById(R.id.tvTotalDistance)
        tvTotalCost = findViewById(R.id.tvTotalCost)
        tvPersonalDistance = findViewById(R.id.tvPersonalDistance)
        tvBusinessDistance = findViewById(R.id.tvBusinessDistance)
        progressBar = findViewById(R.id.progressBar)
        rvReports = findViewById(R.id.rvReports)
        
        db = LocationDatabase.getDatabase(this)

        sessionAdapter = SessionAdapter(
            onMapClick = { _ -> },
            onTypeToggle = { _ -> },
            onDelete = { _ -> },
            onEnd = { _ -> }
        )
        rvReports.layoutManager = LinearLayoutManager(this)
        rvReports.adapter = sessionAdapter

        fetchReportData()
    }

    private fun fetchReportData() {
        progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = db.settingDao().getSetting("api_key")?.value ?: ""
            if (apiKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@ReportsActivity, "API Key missing", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val request = Request.Builder()
                .url("https://travel-access.ddns.net/api/sessions?page=1&limit=10000")
                .header("X-API-Key", apiKey)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            Toast.makeText(this@ReportsActivity, "Failed to fetch reports", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    val bodyString = response.body?.string() ?: ""
                    val result = gson.fromJson(bodyString, SessionResponse::class.java)

                    if (result.success && result.data != null) {
                        val sessions = result.data
                        
                        var totalDist = 0.0
                        var totalCost = 0.0
                        var personalDist = 0.0
                        var businessDist = 0.0

                        for (s in sessions) {
                            val d = s.distance ?: 0.0
                            val c = s.cost ?: 0.0
                            
                            totalDist += d
                            totalCost += c

                            if (s.type == "P") {
                                personalDist += d
                            } else if (s.type == "B") {
                                businessDist += d
                            }
                        }

                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            tvTotalDistance.text = String.format("%.2f KM", totalDist)
                            tvTotalCost.text = String.format("$%.2f", totalCost)
                            tvPersonalDistance.text = String.format("%.2f KM", personalDist)
                            tvBusinessDistance.text = String.format("%.2f KM", businessDist)
                            sessionAdapter.submitList(sessions)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            Toast.makeText(this@ReportsActivity, "No data available", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@ReportsActivity, "Network Error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
