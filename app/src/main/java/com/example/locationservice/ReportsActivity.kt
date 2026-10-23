package com.example.locationservice

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.locationservice.databinding.ActivityReportsBinding
import kotlinx.coroutines.launch
import java.util.Locale

class ReportsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportsBinding
    private lateinit var apiClient: ApiClient
    private var carsList = listOf<CarItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiClient = ApiClient(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { loadReports() }

        setupFilters()
        loadCarsAndReports()
    }

    private fun setupFilters() {
        val years = arrayOf("Any Year", "2024", "2025", "2026")
        binding.spinnerYearFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years)

        binding.btnApplyFilters.setOnClickListener {
            loadReports()
        }
    }

    private fun loadCarsAndReports() {
        lifecycleScope.launch {
            val carsRes = apiClient.getCars()
            carsList = carsRes.getOrDefault(emptyList())

            val carNames = mutableListOf("All Cars")
            carsList.forEach { car ->
                carNames.add(car.description ?: car.licensePlate ?: "Car #${car.id}")
            }
            binding.spinnerCarFilter.adapter = ArrayAdapter(
                this@ReportsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                carNames
            )

            loadReports()
        }
    }

    private fun loadReports() {
        binding.progressBar.visibility = View.VISIBLE

        val selectedCarIdx = binding.spinnerCarFilter.selectedItemPosition
        val selectedCarId = if (selectedCarIdx > 0 && selectedCarIdx <= carsList.size) {
            carsList[selectedCarIdx - 1].id
        } else null

        val selectedYear = binding.spinnerYearFilter.selectedItem?.toString()

        lifecycleScope.launch {
            val result = apiClient.getReports(carId = selectedCarId, year = selectedYear)
            binding.progressBar.visibility = View.GONE

            result.onSuccess { summary ->
                binding.tvTotalDistance.text = String.format(Locale.US, "%.2f KM", summary.totalDistanceKm)
                binding.tvTotalCost.text = String.format(Locale.US, "Estimated Cost: $%.2f", summary.totalCost)
                binding.tvTotalTrips.text = "Total Trips: ${summary.totalTrips}"
                binding.tvBusinessDistance.text = String.format(Locale.US, "%.2f KM", summary.businessDistanceKm)
                binding.tvPersonalDistance.text = String.format(Locale.US, "%.2f KM", summary.personalDistanceKm)
            }.onFailure { err ->
                Toast.makeText(this@ReportsActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
